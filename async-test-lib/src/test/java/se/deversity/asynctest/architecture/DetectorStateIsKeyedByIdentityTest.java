package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * indirection it does not follow. It flags a map or set call whose key argument contains an
 * identity hash anywhere, so a key concatenated, formatted or wrapped around the hash counts too
 * (#763): {@code identityHashCode(lock) + ":" + threadId} collides exactly when the hashes do. It
 * follows the hash one step through a local and one step through a helper whose body is a single
 * {@code return}. A display label that prints an identity hash is fine and is not matched, as long
 * as it is never used as a key. A file that keys by the hash on purpose and tells a collision apart
 * itself is listed, with its reason, in {@code DELIBERATE}.
 */
class DetectorStateIsKeyedByIdentityTest {

    private static final Pattern KEYED_CALL = Pattern.compile(
            "\\.(?:get|put|putIfAbsent|computeIfAbsent|computeIfPresent|compute|remove|containsKey"
                    + "|merge|getOrDefault|add|contains)\\(");

    private static final Pattern HASH = Pattern.compile("System\\.identityHashCode\\(");

    /** A method whose body is one {@code return}: group 1 its name, group 2 what it returns. */
    private static final Pattern RETURNING_HELPER =
            Pattern.compile("\\b(\\w+)\\s*\\([^()]*\\)\\s*\\{\\s*return\\s+([^;]*);");

    /** A local declared with an initializer: group 1 its name, group 2 the initializer. */
    private static final Pattern LOCAL = Pattern.compile(
            "\\b(?:int|Integer|long|Long|String|Object|var)\\s+(\\w+)\\s*=\\s*([^;]*);");

    private static final Set<String> KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "synchronized");

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
            "ThreadLocalMonitor.java", "#574 keys it by identity itself");

    /**
     * Files keyed by an identity hash on purpose, each of which tells a collision apart itself.
     * An entry here needs a reason a reviewer can check against the file.
     */
    private static final Map<String, String> DELIBERATE = Map.of(
            "SpinLocks.java", "keys by hash plus a weak reference, so nothing is retained, and checks "
                    + "Lock.isFor on every lookup: a colliding second object goes undeclared, which "
                    + "can only report an access, never hide one");

    @Test
    @DisplayName("no detector state is keyed by a bare identity hash")
    void noBareIdentityKeys() {
        Map<String, List<String>> offenders = scan();
        assertTrue(offenders.keySet().containsAll(DELIBERATE.keySet()),
                "a file excused as deliberate no longer matches, so its exemption would only hide "
                        + "the next key written there; remove it from DELIBERATE: "
                        + DELIBERATE.keySet() + " vs " + offenders.keySet());
        PENDING.keySet().forEach(offenders::remove);
        DELIBERATE.keySet().forEach(offenders::remove);

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

    @Test
    @DisplayName("an identity hash concatenated or formatted into a key is still a bare identity key")
    void theScanFollowsAHashBuiltIntoAKey() {
        // OptimisticReadValidationDetector before 1eef9c4f; the helper and three of its four map calls are
        // verbatim. The hash reaches the map through a helper, straight in one call and through a
        // local in another, and the gate saw none of it (#763).
        String helper = """
                private static String key(Object lock, Thread thread) {
                    return System.identityHashCode(lock) + ":" + thread.threadId();
                }
                void onRead(Object lock, Thread thread, long stamp) {
                    OptimisticRead replaced =
                        pendingReads.put(key(lock, thread), new OptimisticRead(stamp, thread.getName()));
                }
                void onValidate(Object lock, Thread thread) {
                    String k = key(lock, thread);
                    OptimisticRead read = pendingReads.get(k);
                    pendingReads.remove(k);
                }
                """;
        String concatenated = "void e(Object o, long id) { seen.add(System.identityHashCode(o) + \":\" + id); }";
        String prefixed = "void f(Object o) { states.get(\"lock@\" + System.identityHashCode(o)); }";
        String valueOf = "void g(Object o) { states.remove(String.valueOf(System.identityHashCode(o))); }";
        String formatted = "void h(Object o, long id) {\n"
                + " states.put(String.format(\"%x:%d\", System.identityHashCode(o), id), 1); }";
        String storedConcat = "void i(Object o, long id) { String k = System.identityHashCode(o) + \":\" + id;\n"
                + " states.containsKey(k); }";
        String inValue = "void j(Object o) { labels.put(o, \"pool@\" + System.identityHashCode(o)); }";
        String labelHelper = "String n(Object o) { return \"pool@\" + System.identityHashCode(o); }\n"
                + "void k(Object o) { log(n(o)); labels.put(o, n(o)); }";
        String labelInMessage = "void l(Object m) { String label = \"map@\" + System.identityHashCode(m);\n"
                + " issues.add(String.format(\"%s recursed\", label)); issues.add(new Issue(label)); }";
        String cast = "void p(Object o) { attempts.put((long) System.identityHashCode(o), o); }";

        assertEquals(3, hits(helper).size(), "every map call the helper's key reaches");
        assertEquals(1, hits(concatenated).size());
        assertEquals(1, hits(prefixed).size());
        assertEquals(1, hits(valueOf).size());
        assertEquals(1, hits(formatted).size());
        assertEquals(1, hits(storedConcat).size());
        assertEquals(0, hits(inValue).size(), "a hash in the value is a label, not a key");
        assertEquals(0, hits(labelHelper).size(), "a label helper that never reaches a key is fine");
        assertEquals(0, hits(labelInMessage).size(), "a label written into a message is not a key");
        assertEquals(1, hits(cast).size(), "a cast does not hide the hash");
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

    /**
     * {@return the keyed calls in {@code source} whose key argument carries an identity hash}
     *
     * <p>The hash counts wherever it sits in the key expression, so a key concatenated, formatted
     * or {@code String.valueOf}-wrapped around it is still a bare identity key: two objects whose
     * hashes collide still build the same string. It is followed one step through a helper whose
     * body is a single {@code return}, and one step through a local, in that order, so a local
     * initialized from such a helper is followed too.
     *
     * <p>A helper or local counts only at the key's top level, not inside a nested call: most of
     * them are display labels, and one passed to {@code String.format} or a record constructor is
     * being written into a message. The hash itself counts at any depth, because nobody writes it
     * inline into a key by accident.
     */
    private static List<String> hits(String source) {
        String code = withoutComments(source);
        List<Pattern> carriers = new ArrayList<>();
        Matcher helper = RETURNING_HELPER.matcher(code);
        while (helper.find()) {
            if (!KEYWORDS.contains(helper.group(1)) && HASH.matcher(helper.group(2)).find()) {
                carriers.add(Pattern.compile("\\b" + Pattern.quote(helper.group(1)) + "\\s*\\("));
            }
        }
        List<Pattern> locals = new ArrayList<>();
        Matcher local = LOCAL.matcher(code);
        while (local.find()) {
            String initializer = topLevel(local.group(2));
            if (HASH.matcher(local.group(2)).find()
                    || carriers.stream().anyMatch(c -> c.matcher(initializer).find())) {
                locals.add(Pattern.compile("\\b" + Pattern.quote(local.group(1)) + "\\b"));
            }
        }
        carriers.addAll(locals);

        TreeSet<String> found = new TreeSet<>();
        Matcher call = KEYED_CALL.matcher(code);
        while (call.find()) {
            String key = firstArgument(code, call.end());
            String outer = topLevel(key);
            if (HASH.matcher(key).find() || carriers.stream().anyMatch(c -> c.matcher(outer).find())) {
                found.add(snippet(code, call.start()));
            }
        }
        return List.copyOf(found);
    }

    /** {@return {@code expression} with string literals and everything inside brackets removed} */
    private static String topLevel(String expression) {
        StringBuilder outer = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '"' || c == '\'') {
                i = endOfLiteral(expression, i);
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                depth--;
            }
            if (depth == 0) {
                outer.append(c);
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            }
        }
        return outer.toString();
    }

    /** {@return the call argument starting at {@code from}, up to its top-level comma or close} */
    private static String firstArgument(String code, int from) {
        int depth = 0;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                i = endOfLiteral(code, i);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    return code.substring(from, i);
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                return code.substring(from, i);
            }
        }
        return code.substring(from);
    }

    /** {@return the index of the quote closing the string or char literal opened at {@code open}} */
    private static int endOfLiteral(String code, int open) {
        char quote = code.charAt(open);
        for (int i = open + 1; i < code.length(); i++) {
            if (code.charAt(i) == '\\') {
                i++;
            } else if (code.charAt(i) == quote) {
                return i;
            }
        }
        return code.length();
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
