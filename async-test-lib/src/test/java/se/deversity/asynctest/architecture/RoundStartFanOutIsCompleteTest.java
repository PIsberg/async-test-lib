package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A detector that declares {@code markInvocationStart()} is round-scoped: it needs to hear that a
 * round began, or state from round N pairs with state from round N+1 and gets reported. The call
 * is fanned out by hand from two places, {@code ConcurrencyRunner} (through its phase-1 set) and
 * {@code AsyncTestContext.markInvocationStart()}, and a detector on neither list compiles, runs
 * and reports cross-round pairs with nothing to say why (#698).
 *
 * <p>This does not match names. Each call's receiver is resolved to the declared type of the
 * field it names, so {@code phase1.race} counts for {@code RaceConditionDetector} because that
 * is what the field holds, not because of how either is spelled.
 */
class RoundStartFanOutIsCompleteTest {

    private static final String ROOT_PACKAGE = "se.deversity.asynctest.";
    private static final Pattern DECLARATION = Pattern.compile("\\bvoid\\s+markInvocationStart\\s*\\(\\s*\\)");
    private static final Pattern CALL = Pattern.compile("\\b([A-Za-z_][\\w.]*)\\.markInvocationStart\\s*\\(\\s*\\)");

    /** Which class owns the field a receiver's first segment names, per chain file. */
    private static final Map<String, String> OWNER_OF_PREFIX = Map.of(
            "registry", ROOT_PACKAGE + "DetectorRegistry",
            "phase1", ROOT_PACKAGE + "diagnostics.Phase1DetectorSet");

    /** The runner's local for the run's {@code AsyncTestContext}. */
    private static final String HAND_OFF = "phase2Context";

    @Test
    @DisplayName("every detector declaring markInvocationStart() is called by exactly one chain")
    void everyRoundScopedDetectorHearsTheRoundStart() throws Exception {
        Map<String, Integer> calls = new TreeMap<>();
        for (String declaring : declaringDetectors()) {
            calls.put(declaring, 0);
        }
        assertFalse(calls.isEmpty(), "found no detector declaring markInvocationStart(); the scan is broken");

        int handOffs = 0;
        for (String receiverType : receiverTypes()) {
            if (receiverType.equals(HAND_OFF)) {
                handOffs++;
            } else {
                calls.merge(receiverType, 1, Integer::sum);
            }
        }
        assertEquals(1, handOffs, "ConcurrencyRunner must call " + HAND_OFF
                + ".markInvocationStart() once per round: it is the only way the detectors "
                + "AsyncTestContext fans out to hear that a round began.");

        Map<String, Integer> expected = new TreeMap<>();
        calls.keySet().forEach(name -> expected.put(name, 1));
        assertEquals(expected, calls,
                "0 means a round-scoped detector no chain notifies: add it to "
                        + "AsyncTestContext.markInvocationStart(). 2 means both chains bump it.");
    }

    /** {@return the simple names of the diagnostics classes that declare the method} */
    private static List<String> declaringDetectors() throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(mainSources().resolve("diagnostics"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (DECLARATION.matcher(Files.readString(file)).find()) {
                    names.add(file.getFileName().toString().replace(".java", ""));
                }
            }
        }
        return names;
    }

    /** {@return the declared field type behind every call in the two chains, one entry per call} */
    private static List<String> receiverTypes() throws Exception {
        List<String> types = new ArrayList<>();
        for (Path chain : List.of(mainSources().resolve("AsyncTestContext.java"),
                mainSources().resolve(Path.of("runner", "ConcurrencyRunner.java")))) {
            Matcher call = CALL.matcher(Files.readString(chain));
            while (call.find()) {
                // The runner handing the round start to the context chain, not a detector.
                types.add(call.group(1).equals(HAND_OFF) ? HAND_OFF : fieldType(call.group(1), chain));
            }
        }
        return types;
    }

    private static String fieldType(String receiver, Path chain) throws Exception {
        String[] segments = receiver.split("\\.");
        String owner = segments.length == 1 ? ROOT_PACKAGE + "AsyncTestContext" : OWNER_OF_PREFIX.get(segments[0]);
        if (owner == null || segments.length > 2) {
            throw new AssertionError("Cannot resolve the receiver '" + receiver + "' in " + chain.getFileName()
                    + ". Teach OWNER_OF_PREFIX the class that declares it.");
        }
        String field = segments[segments.length - 1];
        return Class.forName(owner).getDeclaredField(field).getType().getSimpleName();
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
