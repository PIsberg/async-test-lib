package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No detector labels an object the test gave no name by its identity hash (#860).
 *
 * <p>{@link DetectorStateIsKeyedByIdentityTest} keeps identity hashes out of map and set keys;
 * a label that only reaches report text passes it, and is not wrong today. But two live objects
 * share an identity hash often enough that such a label prints two objects as one the moment a
 * report keys or groups by it, which is how #854 found nineteen of them. Unnamed objects are
 * labelled through {@code UnnamedLabels} instead, {@code kind@n} with {@code n} counted per
 * detector, so every label has one shape and no later keyed use can reintroduce the merge.
 */
class ReportLabelsAreNotIdentityHashesTest {

    /**
     * A string literal ending in {@code @}, concatenated with an identity hash, a local holding one,
     * or any {@code hashCode()}: an {@code IdentityKey}'s is the identity hash, and a value hash
     * collides just the same.
     */
    private static final String LABEL = "\"[^\"\\n]*@\"\\s*\\+\\s*(?:Integer\\.toHexString\\(\\s*)?"
            + "(?:System\\.identityHashCode\\(|\\w+\\.hashCode\\(\\)%s)";

    /**
     * A local an identity hash, or any {@code hashCode()}, is assigned to, so {@code "x@" + id} is
     * caught as well. {@code int id = key.hashCode()} on an {@code IdentityKey} hid four labels
     * from a scan that only knew {@code System.identityHashCode} (#918).
     */
    private static final Pattern HASH_LOCAL = Pattern.compile(
            "\\b(?:int|long|var)\\s+(\\w+)\\s*=\\s*(?:System\\.identityHashCode\\(|\\w+\\.hashCode\\(\\))");

    /**
     * Labels that render a value rather than name an object: the text a value whose own
     * {@code toString()} threw is shown as. Grouping by rendered value is the point there (see
     * {@code LibraryStateIsKeyedByIdentityTest}).
     */
    private static final Map<String, String> RENDERED_VALUES = Map.of(
            "CompletableFutureCompletionRaceDetector.java",
            "return value.getClass().getSimpleName() + \"@\" + System.identityHashCode(value);",
            "LambdaLostUpdateDetector.java",
            "return value.getClass().getSimpleName() + \"@\" + System.identityHashCode(value);");

    @Test
    @DisplayName("no detector builds a report label from an identity hash")
    void noIdentityHashLabels() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String exempt = RENDERED_VALUES.get(file.getFileName().toString());
                for (String hit : hits(source)) {
                    if (!hit.equals(exempt)) {
                        offenders.add(root.relativize(file) + ": " + hit);
                    }
                }
            }
        }
        assertEquals(List.of(), offenders,
                "these labels name an unnamed object by its identity hash; take the label from the "
                        + "detector's UnnamedLabels instead (#860)");
    }

    @Test
    @DisplayName("the scan sees a direct hash, a hex hash and a local holding one")
    void theScanSeesEachShape() {
        assertEquals(1, hits("String l = \"map@\" + System.identityHashCode(m);").size());
        assertEquals(1, hits("String l = m.getClass().getSimpleName() + \"@\" + System.identityHashCode(m);").size());
        assertEquals(1, hits("String l = \"task@\" + Integer.toHexString(System.identityHashCode(f));").size());
        assertEquals(1, hits("int id = System.identityHashCode(f);\nString n = \"CompletableFuture@\" + id;").size());
        assertEquals(1, hits("int id = key.hashCode();\nString n = \"monitor@\" + id;").size());
        assertEquals(1, hits("String l = m.getClass().getSimpleName() + \"@\"\n        + Integer.toHexString(System.identityHashCode(m));").size());
        assertTrue(hits(" * a label such as \"map@\" + System.identityHashCode(m)").isEmpty());
        assertEquals(1, hits("String l = \"Arena@\" + key.hashCode();").size());
        assertEquals(1, hits("String l = kind + \"@\" + Integer.toHexString(key.hashCode());").size());
        assertTrue(hits("String l = labels.of(m, \"map\");").isEmpty());
        assertTrue(hits("int id = 3;\nString n = \"x@\" + id;").isEmpty());
    }

    private static List<String> hits(String source) {
        Set<String> locals = new HashSet<>();
        Matcher local = HASH_LOCAL.matcher(source);
        while (local.find()) {
            locals.add(Pattern.quote(local.group(1)) + "\\b");
        }
        StringBuilder carriers = new StringBuilder();
        for (String name : locals) {
            carriers.append('|').append(name);
        }
        Pattern label = Pattern.compile(String.format(LABEL, carriers));
        List<String> found = new ArrayList<>();
        String[] lines = source.split("\\R", -1);
        Matcher hit = label.matcher(source);
        while (hit.find()) {
            int lineIndex = source.substring(0, hit.start()).split("\\R", -1).length - 1;
            String code = lines[lineIndex].strip();
            if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                continue;
            }
            found.add(code);
        }
        return found;
    }
}
