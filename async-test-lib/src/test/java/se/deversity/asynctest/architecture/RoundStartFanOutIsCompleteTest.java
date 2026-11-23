package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.Preset;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A detector that declares {@code markInvocationStart()} is round-scoped: it needs to hear that a
 * round began, or state from round N pairs with state from round N+1 and gets reported (#698).
 *
 * <p>The round start used to be fanned out by hand, one {@code if (x != null)
 * x.markInvocationStart()} line per detector in {@code AsyncTestContext} plus three in
 * {@code ConcurrencyRunner}, and a detector on neither list compiled, ran and reported cross-round
 * pairs with nothing to say why. Now a round-scoped detector implements {@code RoundScoped}, and
 * the context notifies every registry instance that does, so there is no list to forget. These
 * tests pin the three facts that makes true.
 */
class RoundStartFanOutIsCompleteTest {

    private static final String DIAGNOSTICS = "se.deversity.asynctest.diagnostics.";
    private static final Pattern DECLARATION = Pattern.compile("\\bvoid\\s+markInvocationStart\\s*\\(\\s*\\)");
    private static final Pattern CALL = Pattern.compile("\\.markInvocationStart\\s*\\(\\s*\\)");

    /**
     * Classes that declare {@code markInvocationStart()} but are not registry detectors. If one
     * gets a {@code DetectorType}, it must leave this map, and the runtime check below finds it.
     */
    private static final Map<String, String> NOT_REGISTRY_DETECTORS = Map.of(
            "LazyInitValidator", "has no DetectorType; a test that drives it across rounds closes "
                    + "each round itself (#764)",
            "SelfGuard", "its nested Scope is the context's own sharing clock, which the context "
                    + "bumps itself before the detectors");

    @Test
    @DisplayName("every round-scoped detector is a registry instance, so one loop reaches them all")
    void everyRoundScopedDetectorIsARegistryInstance() throws Exception {
        Set<String> declared = declaringDetectors();
        assertFalse(declared.isEmpty(), "found no detector declaring markInvocationStart(); the scan is broken");

        AsyncTestContext context = new AsyncTestContext(AsyncTestConfig.builder().preset(Preset.ALL).build());
        Field registryField = AsyncTestContext.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        Object registry = registryField.get(context);
        Method instances = registry.getClass().getDeclaredMethod("instances");
        instances.setAccessible(true);
        Set<String> inRegistry = new TreeSet<>();
        for (Object detector : ((Map<?, ?>) instances.invoke(registry)).values()) {
            if (declaresRoundStart(detector.getClass())) {
                inRegistry.add(detector.getClass().getSimpleName());
            }
        }
        assertEquals(declared, inRegistry,
                "a round-scoped detector the registry does not build is never told a round began; "
                        + "give it a DetectorType row, or list it in NOT_REGISTRY_DETECTORS with why");
    }

    @Test
    @DisplayName("every round-scoped detector implements RoundScoped")
    void everyRoundScopedDetectorImplementsRoundScoped() throws Exception {
        Class<?> roundScoped = Class.forName(DIAGNOSTICS + "RoundScoped");
        Set<String> missing = new TreeSet<>();
        for (String name : declaringDetectors()) {
            if (!roundScoped.isAssignableFrom(Class.forName(DIAGNOSTICS + name))) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(), "these declare markInvocationStart() but do not implement "
                + "RoundScoped, so the context's round-start loop skips them: " + missing);
    }

    @Test
    @DisplayName("the round start is fanned out by one loop, not by a hand-kept list")
    void theFanOutIsOneLoop() throws IOException {
        long contextCalls = calls(mainSources().resolve("AsyncTestContext.java"));
        assertEquals(2, contextCalls, "AsyncTestContext should make two markInvocationStart() calls: "
                + "its own sharing clock and the RoundScoped loop. A per-detector line next to the loop "
                + "bumps that detector twice per round.");
        long runnerCalls = calls(mainSources().resolve(Path.of("runner", "ConcurrencyRunner.java")));
        assertEquals(1, runnerCalls, "ConcurrencyRunner should make one markInvocationStart() call, "
                + "handing the round start to the context. The phase-1 detectors are the registry's "
                + "own instances, so the loop already reaches them.");
    }

    private static boolean declaresRoundStart(Class<?> type) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals("markInvocationStart") && m.getParameterCount() == 0) {
                return true;
            }
        }
        return false;
    }

    private static long calls(Path source) throws IOException {
        Matcher call = CALL.matcher(Files.readString(source));
        long count = 0;
        while (call.find()) {
            count++;
        }
        return count;
    }

    /** {@return the simple names of the diagnostics classes that declare the method, minus the exceptions} */
    private static Set<String> declaringDetectors() throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.list(mainSources().resolve("diagnostics"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                // RoundScoped declares the method as the contract the others implement.
                if (DECLARATION.matcher(Files.readString(file)).find() && !NOT_REGISTRY_DETECTORS.containsKey(name)
                        && !name.equals("RoundScoped")) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static Path mainSources() {
        return repoRoot().resolve(Path.of("async-test-lib", "src", "main", "java", "se", "deversity", "asynctest"));
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not find the reactor root above " + Path.of("").toAbsolutePath());
    }
}
