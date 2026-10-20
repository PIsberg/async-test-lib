package se.deversity.asynctest.agent;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AgentCollectionHooks;
import se.deversity.asynctest.AgentConcurrencyUtilHooks;
import se.deversity.asynctest.AgentSharedInstanceHooks;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every call the agent substitutes is a scheduling point of a controlled schedule (#1023).
 *
 * <p>{@link ControlledScheduleHookPointsTest} shows the behaviour on two shapes; this is what keeps
 * it true for the several hundred hooks it cannot run one by one. A hook added without a point
 * still passes every other test: it records, it delegates, and the schedule simply runs the whole
 * check-then-act through it in one turn, so a seed can never split it. Read from the bytecode, a
 * hook reaches a point when it calls one of {@code ControlledSchedule}'s point entries or a static
 * method of its own class that does, which is how an overload that delegates to its sibling is
 * counted. The entries are {@code point()} and the three that are a point naming what the worker
 * waits for: {@code acquiring} (a lock), {@code joining} (a thread) and {@code awaitingTask} (a
 * task's result, #1049).
 */
class SubstitutionHooksArePointsTest {

    private static final String POINT_OWNER = "se/deversity/asynctest/telemetry/ControlledSchedule";

    /** The {@code ControlledSchedule} methods that are scheduling points. */
    private static final Set<String> POINT_ENTRIES = Set.of("point", "acquiring", "joining", "awaitingTask");

    /**
     * Calls the field weaver inserts beside an instruction rather than in place of one. Each
     * observes an operation that has already happened, or is not an operation at all, so a point
     * there would only add a step between an operation and its own observation.
     */
    private static final Set<String> INSERTED_OBSERVATIONS = Set.of(
            // The take of an ownership hand-off, called after the getAndSet it observes (#555, #664).
            "ownershipTaken", "ownershipOffered", "ownershipRefused", "ownershipDrained",
            // StampedLock optimistic reads, called around the read they bracket.
            "optimisticReadStarted", "optimisticReadValidated", "optimisticReadIgnored");

    /** Hook-class methods that are not substitutions. */
    private static final Set<String> NOT_OPERATIONS = Set.of(
            // Emitted in place of the POP after an offer, to say its result was dropped.
            "offerResultDiscarded");

    @Test
    void everyCollectionWeaverHookReachesAPoint() {
        List<String> missing = new ArrayList<>();
        for (Class<?> hooks : List.of(AgentCollectionHooks.class, AgentConcurrencyUtilHooks.class,
                AgentSharedInstanceHooks.class)) {
            ClassCalls calls = ClassCalls.of(hooks);
            for (String method : calls.publicStatic) {
                String name = method.substring(0, method.indexOf('('));
                if (!NOT_OPERATIONS.contains(name) && !calls.reachesPoint(method, new HashSet<>())) {
                    missing.add(hooks.getSimpleName() + "." + method);
                }
            }
        }
        assertTrue(missing.isEmpty(), "these substitution hooks never reach ControlledSchedule.point(),"
                + " so a controlled schedule cannot change hands at the call they replace; add the"
                + " point as the hook's first statement: " + missing);
    }

    @Test
    void everyRegistryHookTheFieldWeaverNamesReachesAPoint() {
        // The weaver builds a hook's name from the JDK method's and a suffix naming the receiver
        // ("compareAndSet" + "AtomicInteger"), so the suffixes are what its constant pool holds.
        Set<String> suffixes = new HashSet<>();
        Set<String> constants = new HashSet<>();
        for (Class<?> member : FieldAccessWeaver.class.getNestMembers()) { // the visitors are nested
            constants.addAll(stringConstants(member));
        }
        for (String constant : constants) {
            if (constant.length() >= 3 && Character.isUpperCase(constant.charAt(0))
                    && constant.chars().allMatch(Character::isLetter)) {
                suffixes.add(constant);
            }
        }
        ClassCalls calls = ClassCalls.of(TelemetryRegistry.class);
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (String method : calls.publicStatic) {
            String name = method.substring(0, method.indexOf('('));
            boolean named = suffixes.stream().anyMatch(
                    suffix -> name.endsWith(suffix) && name.length() > suffix.length()
                            && Character.isLowerCase(name.charAt(0)));
            if (!named || INSERTED_OBSERVATIONS.contains(name)) {
                continue;
            }
            checked++;
            if (!calls.reachesPoint(method, new HashSet<>())) {
                missing.add(method);
            }
        }
        assertTrue(checked >= 100, "only " + checked + " TelemetryRegistry hooks matched a hook-name suffix in"
                + " FieldAccessWeaver's constants, so the scan no longer sees the weaver's tables");
        assertTrue(missing.isEmpty(), "these TelemetryRegistry hooks are named by FieldAccessWeaver and"
                + " never reach ControlledSchedule.point(); a substituted atomic or field call there"
                + " cannot be split by a seed. Add the point, or name the hook in"
                + " INSERTED_OBSERVATIONS with the reason it observes rather than replaces: " + missing);
    }

    /** Each method's static calls, read from the class file. */
    private static final class ClassCalls {
        final String owner;
        final Set<String> publicStatic = new TreeSet<>();
        final Map<String, List<String[]>> invokes = new LinkedHashMap<>();

        private ClassCalls(String owner) {
            this.owner = owner;
        }

        static ClassCalls of(Class<?> type) {
            ClassCalls calls = new ClassCalls(type.getName().replace('.', '/'));
            read(type).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    String key = name + descriptor;
                    if ((access & Opcodes.ACC_PUBLIC) != 0 && (access & Opcodes.ACC_STATIC) != 0) {
                        calls.publicStatic.add(key);
                    }
                    List<String[]> out = calls.invokes.computeIfAbsent(key, k -> new ArrayList<>());
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method,
                                                    String desc, boolean isInterface) {
                            if (opcode == Opcodes.INVOKESTATIC) {
                                out.add(new String[] {owner, method, desc});
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG);
            return calls;
        }

        boolean reachesPoint(String method, Set<String> seen) {
            if (!seen.add(method)) {
                return false;
            }
            for (String[] call : invokes.getOrDefault(method, List.of())) {
                if (POINT_OWNER.equals(call[0]) && POINT_ENTRIES.contains(call[1])) {
                    return true;
                }
                if (owner.equals(call[0]) && reachesPoint(call[1] + call[2], seen)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Set<String> stringConstants(Class<?> type) {
        Set<String> constants = new HashSet<>();
        read(type).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String s) {
                            constants.add(s);
                        }
                    }

                    // name + "Int" compiles to a string-concatenation call site whose recipe holds
                    // the constant between \u0001 argument markers, never as an LDC.
                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor,
                                                       net.bytebuddy.jar.asm.Handle bootstrap,
                                                       Object... arguments) {
                        for (Object argument : arguments) {
                            if (argument instanceof String recipe) {
                                for (String part : recipe.split("[\u0001\u0002]")) {
                                    if (!part.isEmpty()) {
                                        constants.add(part);
                                    }
                                }
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        return constants;
    }

    private static ClassReader read(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("no class file for " + type.getName());
            }
            return new ClassReader(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
