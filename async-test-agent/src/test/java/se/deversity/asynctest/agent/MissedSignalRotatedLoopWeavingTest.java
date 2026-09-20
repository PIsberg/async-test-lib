package se.deversity.asynctest.agent;

import com.example.agentfixture.WaitLoopShapesSample;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AgentMonitorHooks;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The same wait shapes as {@code MissedSignalBackEdgeWeavingTest}, compiled by ECJ (#710).
 *
 * <p>The back-edge rule was written against javac, which puts a {@code while} loop's test at the
 * top and closes the loop with a {@code goto}. ECJ rotates the loop instead: it emits
 * {@code goto test; body; test: if (...) goto body}, so a correct predicate loop closes with a
 * <em>conditional</em> back-edge and puts its test after the wait. Under the javac-only rule that
 * reads as {@code do { wait(); } while (!ready)} and the wait is reported, so a user compiling
 * with Eclipse would see a false {@code MISSED_SIGNAL} on correct code.
 *
 * <p>This class compiles {@link WaitLoopShapesSample} with the real ECJ rather than a hand-built
 * imitation of its output, so the gate keeps tracking what that compiler emits across version
 * bumps. The expectations are identical to the javac gate's: the decision is a property of the
 * source shape, and a compiler may not change it.
 *
 * <p>kotlinc is the other candidate the issue named. Checked by hand against kotlinc 2.4.10, it
 * emits the javac shape for {@code while} and a conditional back-edge with no entry {@code goto}
 * for {@code do}/{@code while}, so both halves of the rule already hold there;
 * {@code docs/agent/attaching.md} records that reading and the version it was taken at.
 */
class MissedSignalRotatedLoopWeavingTest {

    private static final String SAMPLE = WaitLoopShapesSample.class.getName();

    private static Map<String, Integer> marksByMethod;

    @BeforeAll
    static void compileWithEcjAndWeave() {
        byte[] compiled = compileWithEcj();
        // Compound: the sample comes from ECJ's output, everything it refers to from the test
        // class loader. A locator holding only the sample cannot resolve java.lang.Object.
        ClassFileLocator locator = new ClassFileLocator.Compound(
                ClassFileLocator.Simple.of(SAMPLE, compiled),
                ClassFileLocator.ForClassLoader.of(
                        MissedSignalRotatedLoopWeavingTest.class.getClassLoader()));
        byte[] woven = new ByteBuddy()
                .redefine(TypePool.Default.of(locator).describe(SAMPLE).resolve(), locator)
                .visit(CollectionAccessWeaver.monitorSubstitutions(AgentMonitorHooks.class).get(0))
                .make()
                .getBytes();
        marksByMethod = countMarks(woven);
    }

    /**
     * {@return the sample compiled by ECJ}
     *
     * <p>ECJ registers a JSR-199 compiler, so the same {@code JavaCompiler} calls that would drive
     * javac drive it. {@link ToolProvider#getSystemJavaCompiler()} would hand back javac and test
     * nothing, which is why the service loader is asked for the Eclipse one by name.
     */
    private static byte[] compileWithEcj() {
        JavaCompiler ecj = null;
        for (JavaCompiler candidate : ServiceLoader.load(JavaCompiler.class,
                MissedSignalRotatedLoopWeavingTest.class.getClassLoader())) {
            if (candidate.getClass().getName().contains("eclipse")) {
                ecj = candidate;
                break;
            }
        }
        assertTrue(ecj != null,
                "no Eclipse compiler on the test classpath. This gate exists to weave what ECJ "
                        + "emits, so a missing compiler is a build problem, not a reason to skip: "
                        + "org.eclipse.jdt:ecj is a test-scoped dependency of this module.");

        Path source = locateSampleSource();
        Path classes;
        try {
            classes = Files.createTempDirectory("ecj-wait-shapes");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean ok;
        try (StandardJavaFileManager files = ecj.getStandardFileManager(diagnostics, null, null)) {
            ok = ecj.getTask(null, files, diagnostics,
                    List.of("-source", "21", "-target", "21", "-nowarn", "-d", classes.toString()),
                    null,
                    files.getJavaFileObjects(source.toFile())).call();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!ok) {
            StringBuilder message = new StringBuilder("ECJ refused the sample:");
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                message.append(System.lineSeparator()).append(d);
            }
            return fail(message.toString());
        }

        try {
            return Files.readAllBytes(classes.resolve(SAMPLE.replace('.', '/') + ".class"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@return the sample's source file}
     *
     * <p>Found by walking up from the working directory rather than hard-coding a module-relative
     * path, because Maven and Gradle start a test with different ones. A copy under
     * {@code src/test/resources} would compile without the walk and drift from the class the javac
     * gate reads, which is the one thing this test may not allow: both gates must see one source.
     */
    private static Path locateSampleSource() {
        String relative = "async-test-agent/src/test/java/" + SAMPLE.replace('.', '/') + ".java";
        for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return fail("could not find " + relative + " above " + Paths.get("").toAbsolutePath());
    }

    /** {@return how many loop back-edge marks the woven class carries, per method} */
    private static Map<String, Integer> countMarks(byte[] woven) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        new ClassReader(woven).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                counts.putIfAbsent(name, 0);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method,
                                                String desc, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && "loopBackEdge".equals(method) && "()V".equals(desc)) {
                            counts.merge(name, 1, Integer::sum);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return counts;
    }

    @Test
    @DisplayName("a rotated predicate loop around a wait is marked, in one method and across one")
    void rotatedPredicateLoopsAreMarked() {
        assertEquals(1, marksByMethod.get("whileLoop"),
                "ECJ compiles while (!ready) { ...; wait(); } to goto test; body; test: ifeq "
                        + "body, so the back-edge is the predicate test itself and the test sits "
                        + "after the wait. It is the same correct source the javac gate marks, "
                        + "and a choice of compiler may not turn it into a reported bug. Marks "
                        + "were " + marksByMethod);
        assertEquals(1, marksByMethod.get("crossMethodLoop"),
                "the rotated shape with the wait one method away, which needs both the buffering "
                        + "pass and the rotated back-edge rule to be recognised. Marks were "
                        + marksByMethod);
    }

    @Test
    @DisplayName("a wait entered before the predicate is read is not marked, in any rotated shape")
    void waitsAheadOfTheirPredicateAreNotMarked() {
        assertEquals(0, marksByMethod.get("doWhileLoop"),
                "ECJ closes do { ...; wait(); } while (!ready) with a conditional back-edge too, "
                        + "and this is the shape that must stay unmarked. What separates it from "
                        + "the rotated while is the entry goto: a rotated loop jumps to its test "
                        + "before it runs the body, a do/while falls straight into the body. "
                        + "Marks were " + marksByMethod);
        assertEquals(0, marksByMethod.get("doWhileAlwaysWaits"),
                "the do/while DoWhileWaitHandOffBean runs, which reaches its wait on every "
                        + "round. Marks were " + marksByMethod);
        assertEquals(0, marksByMethod.get("continueLoop"),
                "a loop closed by continue keeps a goto back-edge under ECJ as well, with "
                        + "nothing read between the loop's head and the wait. Marks were "
                        + marksByMethod);
        assertEquals(0, marksByMethod.get("endlessWait"),
                "while (true) { wait(); } has a goto back-edge and no predicate at all. Marks "
                        + "were " + marksByMethod);
        assertEquals(0, marksByMethod.get("breakThenDoWhile"),
                "a break in front of a do/while leaves a goto immediately before the loop head, "
                        + "which is the rotated loop's signature minus the part that matters: the "
                        + "goto jumps clear of the loop rather than into its test. Marks were "
                        + marksByMethod);
        assertEquals(0, marksByMethod.get("ifGuarded"),
                "if (!ready) wait() has no backward jump under any compiler: the case the mark "
                        + "exists to tell apart. Marks were " + marksByMethod);
        assertEquals(0, marksByMethod.get("awaitOnce"),
                "the helper holds the wait and no loop; the mark belongs to its caller. Marks "
                        + "were " + marksByMethod);
    }
}
