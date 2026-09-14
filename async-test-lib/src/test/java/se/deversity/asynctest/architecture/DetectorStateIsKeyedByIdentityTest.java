package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refuses a map or set keyed by a bare {@code System.identityHashCode} anywhere in the library
 * (#564).
 *
 * <p>An identity hash is not unique. Two live objects share one about half the time once some
 * 54,000 are tracked, and a map keyed by the bare hash merges them: the detector attributes one
 * object's events to the other, in either direction and with nothing printed. 79 detector classes
 * were keyed that way. They now key by {@code IdentityKey}, or by a value that is already unique,
 * such as a thread id. This test is what stops the 80th: a key that merely hashes like identity
 * reads as correct in review.
 *
 * <p>It reads the source rather than the bytecode, so it is a pattern and can be evaded by an
 * indirection it does not follow. It follows the two shapes the 79 used: an identity hash passed
 * straight into a map or set call, and one stored in a local first. A display label that prints an
 * identity hash is fine and is not matched.
 */
class DetectorStateIsKeyedByIdentityTest {

    private static final String KEYED_CALL =
            "\\.(?:get|put|putIfAbsent|computeIfAbsent|computeIfPresent|compute|remove|containsKey"
                    + "|merge|getOrDefault|add|contains)\\(\\s*";

    private static final Pattern DIRECT = Pattern.compile(KEYED_CALL + "System\\.identityHashCode\\(");

    private static final Pattern STORED =
            Pattern.compile("\\b(?:int|Integer|var)\\s+(\\w+)\\s*=\\s*System\\.identityHashCode\\(");

    /**
     * Files still keyed by a bare identity hash because an open pull request rewrites the same
     * lines, and converting them here too would give both a conflict to resolve. Each converts in
     * the follow-up that empties this map, which is what closes #564.
     *
     * <p>Deliberately not checked for staleness. The pull requests merge in an order nobody here
     * controls, and a check that failed once one of them had fixed its file would turn the main
     * branch red on a merge that did nothing wrong.
     */
    private static final Map<String, String> PENDING = Map.of(
            "ExecutorShutdownDetector.java", "#573 keys it by identity itself",
            "ThreadLocalMonitor.java", "#574 keys it by identity itself",
            "LockUpgradeDeadlockDetector.java", "#572 rewrites its read-hold map",
            "LockDowngradeDetector.java", "#572 rewrites its write-acquire forwarding",
            "ReadWriteLockMonitor.java", "#577 edits its report class");

    @Test
    @DisplayName("no detector state is keyed by a bare identity hash")
    void noBareIdentityKeys() {
        Map<String, List<String>> offenders = scan();
        PENDING.keySet().forEach(offenders::remove);

        assertTrue(offenders.isEmpty(),
                "these files key a map or set by System.identityHashCode, which merges two objects "
                        + "whose hashes collide and silently attributes one's events to the other. "
                        + "Key by IdentityKey instead (it caches the hash and compares referents "
                        + "with ==), or by a value that is already unique, such as a thread id: "
                        + offenders);
    }

    @Test
    @DisplayName("the scan finds the shapes it claims to find")
    void theScanSeesBothShapes() {
        String direct = "void a(Object o) { states.computeIfAbsent(System.identityHashCode(o), k -> 1); }";
        String stored = "void b(Object o) { int id = System.identityHashCode(o);\n seen.add(id); }";
        String label = "String c(Object o) { return \"pool@\" + System.identityHashCode(o); }";
        String commented = "// states.get(System.identityHashCode(o))\nvoid d() {}";

        assertEquals(1, hits(direct).size());
        assertEquals(1, hits(stored).size());
        assertEquals(0, hits(label).size(), "a label is not a key");
        assertEquals(0, hits(commented).size(), "a comment is not code");
    }

    private static Map<String, List<String>> scan() {
        Path root = Path.of("src/main/java");
        Map<String, List<String>> offenders = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                List<String> found = hits(Files.readString(file, StandardCharsets.UTF_8));
                if (!found.isEmpty()) {
                    offenders.put(file.getFileName().toString(), found);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + root.toAbsolutePath()
                    + "; this test reads the module's own sources and runs from the module directory", e);
        }
        return offenders;
    }

    private static List<String> hits(String source) {
        String code = withoutComments(source);
        TreeSet<String> found = new TreeSet<>();
        Matcher direct = DIRECT.matcher(code);
        while (direct.find()) {
            found.add(snippet(code, direct.start()));
        }
        Matcher stored = STORED.matcher(code);
        while (stored.find()) {
            Matcher use = Pattern.compile(KEYED_CALL + Pattern.quote(stored.group(1)) + "\\b").matcher(code);
            while (use.find()) {
                found.add(snippet(code, use.start()));
            }
        }
        return List.copyOf(found);
    }

    private static String snippet(String code, int at) {
        int end = code.indexOf('\n', at);
        return code.substring(at, end < 0 ? code.length() : end).strip();
    }

    /** {@return {@code source} with block and line comments removed} */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }
}
