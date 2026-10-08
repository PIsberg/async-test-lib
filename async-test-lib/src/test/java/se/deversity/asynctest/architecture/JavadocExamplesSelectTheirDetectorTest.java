package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.Preset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A javadoc example that calls a detector's accessor also selects that detector (#955).
 *
 * <p>Since 1.13.0 a bare {@code @AsyncTest} runs {@link Preset#ESSENTIALS} (#923), and an accessor
 * for a detector outside it throws {@code IllegalStateException: Detector not active}. The
 * Markdown snippets were rewritten with the flip; 39 class javadocs were not, and the javadoc jar
 * and the Pages site published examples that throw when copied. A javadoc comment in main code
 * that calls {@code AsyncTestContext.x()} for a detector outside {@code ESSENTIALS} must name
 * {@code DetectorType.X} (in {@code includes}, or as a {@code {@link DetectorType#X}} that says it
 * must be enabled) or say {@code detectAll = true}.
 */
class JavadocExamplesSelectTheirDetectorTest {

    private static final Pattern ACCESSOR = Pattern.compile(
            "public static \\w+(?:<[^>]*>)? (\\w+)\\(\\)\\s*\\{\\s*return require\\(DetectorType\\.(\\w+)");
    private static final Pattern JAVADOC = Pattern.compile("/\\*\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern CALL = Pattern.compile("AsyncTestContext\\.(\\w+)\\(\\)");
    private static final Path MAIN = Path.of("src/main/java");

    @Test
    @DisplayName("every javadoc example selects the detector whose accessor it calls")
    void examplesSelectTheirDetector() {
        Map<String, String> typeOf = accessorTypes();
        Set<DetectorType> essentials = Preset.ESSENTIALS.enabled();
        TreeSet<String> throwing = new TreeSet<>();
        for (Path file : mainSources()) {
            String source = read(file);
            Matcher doc = JAVADOC.matcher(source);
            while (doc.find()) {
                String comment = doc.group();
                Matcher call = CALL.matcher(comment);
                while (call.find()) {
                    String type = typeOf.get(call.group(1));
                    if (type == null || essentials.contains(DetectorType.valueOf(type))
                            || comment.contains("DetectorType." + type)
                            || comment.contains("DetectorType#" + type)
                            || comment.contains("detectAll = true")) {
                        continue;
                    }
                    int line = 1 + (int) source.substring(0, doc.start()).chars().filter(c -> c == '\n').count();
                    throwing.add(MAIN.relativize(file).toString().replace('\\', '/') + ":" + line
                            + " calls " + call.group(1) + "() without DetectorType." + type);
                }
            }
        }
        assertTrue(throwing.isEmpty(), throwing.size() + " javadoc examples call a detector outside"
                + " ESSENTIALS without selecting it, so copied as written they throw 'Detector not"
                + " active' under the 1.13.0 default; add includes = DetectorType.X (#955):\n  "
                + String.join("\n  ", throwing));
    }

    @Test
    @DisplayName("the accessor map covers every detector type")
    void theAccessorMapIsComplete() {
        assertEquals(DetectorType.values().length, new TreeSet<>(accessorTypes().values()).size(),
                "every DetectorType has an accessor read through require(DetectorType.X, ...);"
                        + " a smaller map means this scan stopped seeing some of them");
    }

    /** {@return accessor name to the DetectorType constant it requires, read from AsyncTestContext} */
    private static Map<String, String> accessorTypes() {
        Map<String, String> map = new HashMap<>();
        Matcher m = ACCESSOR.matcher(read(MAIN.resolve("se/deversity/asynctest/AsyncTestContext.java")));
        while (m.find()) {
            map.put(m.group(1), m.group(2));
        }
        return map;
    }

    private static List<Path> mainSources() {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
