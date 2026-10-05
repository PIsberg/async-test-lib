package se.deversity.asynctest.architecture;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code @AIThreadSafe} claim in main code has a test that runs the class concurrently and
 * names it with {@code @ConcurrencyTestFor} (#906).
 *
 * <p>An {@code @AIThreadSafe} note is a specific claim, and nothing used to require a test that
 * could fail when it broke. {@code LicenseGuard} claimed at-most-once gate execution while its only
 * concurrency test asserted a {@code ConcurrentHashMap}'s size, which holds even when the gate runs
 * on every thread; {@code LicenseValidationCache} claimed safe concurrent writers and had no
 * concurrent test at all, and the first one found a shipped defect (#904).
 *
 * <p>The annotation is source-retained, so this gate reads source. A class counts as claiming when
 * a line of its file starts with {@code @AIThreadSafe}; a test counts as driving it when it carries
 * {@code @ConcurrencyTestFor} naming the class and runs threads through {@code @AsyncTest} or a
 * {@code CyclicBarrier}. Detectors are exempt as a package: {@code @AsyncTest} feeds them by design,
 * and {@code DetectorAccuracyEvalTest} and the corpus lanes run each one concurrently in both
 * directions. Any other exemption goes in {@link #EXEMPT} with its reason.
 */
class ThreadSafetyClaimsAreTestedConcurrentlyTest {

    private static final List<String> MODULES =
            List.of("async-test-lib", "async-test-agent", "async-test-analysis");

    /** The detectors' package, exempt as a whole; see the class javadoc. */
    private static final String DETECTOR_PACKAGE_DIR = "/se/deversity/asynctest/diagnostics/";

    /** Simple class name to the reason its claim cannot be driven concurrently. Empty today. */
    private static final Map<String, String> EXEMPT = Map.of();

    private static final Pattern CLAIM = Pattern.compile("(?m)^ *@AIThreadSafe(?![A-Za-z])");
    private static final Pattern MARKER = Pattern.compile("@ConcurrencyTestFor[(]([^)]*)[)]");
    private static final Pattern CLASS_LITERAL =
            Pattern.compile("([A-Z][A-Za-z0-9_]*)[.]class(?![A-Za-z0-9_])");
    private static final Pattern DRIVER = Pattern.compile("@AsyncTest(?![A-Za-z])|CyclicBarrier");

    @Test
    @DisplayName("every @AIThreadSafe class outside the detectors is named by a concurrent test")
    void everyClaimHasAConcurrentTest() {
        Set<String> untested = new TreeSet<>(claims().keySet());
        untested.removeAll(EXEMPT.keySet());
        untested.removeAll(markedTargets().keySet());
        assertTrue(untested.isEmpty(), "these classes carry @AIThreadSafe but no test names them with"
                + " @ConcurrencyTestFor; add a test that runs them on several threads at once and can"
                + " fail when the claim breaks, or an EXEMPT entry with the reason: " + untested);
    }

    @Test
    @DisplayName("every @ConcurrencyTestFor names a class that makes the claim")
    void everyMarkerNamesAClaim() {
        Map<String, String> stale = new TreeMap<>(markedTargets());
        stale.keySet().removeAll(claims().keySet());
        assertTrue(stale.isEmpty(), "@ConcurrencyTestFor names a class with no @AIThreadSafe claim"
                + " (class -> test); drop the name or restore the claim: " + stale);
    }

    @Test
    @DisplayName("every test carrying @ConcurrencyTestFor runs threads concurrently")
    void everyMarkedTestDrivesConcurrently() {
        Set<String> idle = new TreeSet<>();
        for (Path test : testSources()) {
            String source = read(test);
            if (MARKER.matcher(source).find() && !DRIVER.matcher(source).find()) {
                idle.add(relative(test));
            }
        }
        assertTrue(idle.isEmpty(), "a test marked @ConcurrencyTestFor must run threads through"
                + " @AsyncTest or a CyclicBarrier: " + idle);
    }

    @Test
    @DisplayName("the scan sees the claims it exists for, so a broken scan cannot pass empty")
    void theScanSeesTheKnownClaims() {
        Map<String, String> claims = claims();
        for (String known : List.of("LicenseGuard", "LicenseValidationCache", "AsyncTestContext")) {
            assertTrue(claims.containsKey(known), "no @AIThreadSafe found on " + known + ": " + claims);
        }
        assertTrue(EXEMPT.keySet().stream().allMatch(claims::containsKey),
                "an EXEMPT entry names a class that no longer makes the claim: " + EXEMPT.keySet());
    }

    /** {@return simple class name to file, for every non-detector main class claiming thread safety} */
    private static Map<String, String> claims() {
        Map<String, String> claims = new TreeMap<>();
        for (Path source : sources("main")) {
            String path = relative(source);
            if (!path.contains(DETECTOR_PACKAGE_DIR) && CLAIM.matcher(read(source)).find()) {
                String name = source.getFileName().toString();
                claims.put(name.substring(0, name.length() - ".java".length()), path);
            }
        }
        return claims;
    }

    /** {@return simple class name to the test that names it in {@code @ConcurrencyTestFor}} */
    private static Map<String, String> markedTargets() {
        Map<String, String> targets = new TreeMap<>();
        for (Path test : testSources()) {
            Matcher marker = MARKER.matcher(read(test));
            while (marker.find()) {
                Matcher literal = CLASS_LITERAL.matcher(marker.group(1));
                while (literal.find()) {
                    targets.put(literal.group(1), test.getFileName().toString());
                }
            }
        }
        return targets;
    }

    /** {@return every test source, minus the marker's declaration and this gate, which name it} */
    private static List<Path> testSources() {
        Set<String> self = Set.of("ConcurrencyTestFor.java",
                ThreadSafetyClaimsAreTestedConcurrentlyTest.class.getSimpleName() + ".java");
        return sources("test").stream()
                .filter(p -> !self.contains(p.getFileName().toString()))
                .toList();
    }

    private static List<Path> sources(String sourceSet) {
        Path root = repoRoot();
        return MODULES.stream()
                .map(module -> root.resolve(module).resolve("src").resolve(sourceSet).resolve("java"))
                .filter(Files::isDirectory)
                .flatMap(ThreadSafetyClaimsAreTestedConcurrentlyTest::javaFiles)
                .toList();
    }

    private static Stream<Path> javaFiles(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + dir, e);
        }
    }

    private static String relative(Path path) {
        return repoRoot().relativize(path).toString().replace(File.separatorChar, '/');
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    /** Walks up from the working directory to the reactor root, as Maven and Gradle both start below it. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))
                    && Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not find the reactor root above "
                + Path.of("").toAbsolutePath());
    }
}
