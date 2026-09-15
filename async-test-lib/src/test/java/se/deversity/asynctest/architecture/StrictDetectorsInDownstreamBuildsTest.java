package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import se.deversity.asynctest.DetectorFailurePolicy;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@value DetectorFailurePolicy#STRICT_PROPERTY} on in every build that measures or proves
 * detector behaviour outside this module (#612).
 *
 * <p>{@link DetectorFailurePolicy} contains a detector that throws during analysis: one stderr line
 * and the sweep goes on, which is right for a consumer. In a build whose output is a claim about
 * what the detectors see, the same containment turns a crash into silence. That is how #605 went
 * unnoticed: {@code AtomicityValidator} threw during analysis in corpus lane one on two JDKs, every
 * row that expects that detector to stay quiet passed, and both corpus jobs stayed green. The root
 * build already fails on it; this test holds the corpus lanes, the consumer fixtures and the
 * examples to the same rule, so a lane added later cannot quietly miss the switch.
 *
 * <p>The disabled-demo audit ({@code example-demos.yml}) is the one deliberate exception, and the
 * last test pins it: there a demonstration that <em>fails</em> is the pass condition, so a crash
 * turned into a failure would read as a demonstration that fired.
 */
class StrictDetectorsInDownstreamBuildsTest {

    private static final String PROPERTY = DetectorFailurePolicy.STRICT_PROPERTY;

    /** Corpus-eval runs five lanes today; fewer found means the parse no longer sees them. */
    private static final int MIN_CORPUS_LANES = 5;

    private static final Pattern GRADLE_STRICT = Pattern.compile(
            "systemProperty\\(\\s*\"" + Pattern.quote(PROPERTY) + "\"\\s*,\\s*\"true\"\\s*\\)");

    /** A shell line in a workflow that runs the examples reactor through Maven. */
    private static final Pattern EXAMPLES_MAVEN_RUN = Pattern.compile(
            "^.*\\bmvn\\b.*-f examples/pom\\.xml.*$", Pattern.MULTILINE);

    @Test
    @DisplayName("every corpus-eval lane fails on a detector that throws during analysis")
    void everyCorpusLaneIsStrict() {
        Element surefire = surefirePlugin(parse(repoRoot().resolve("corpus-eval/pom.xml")));
        List<String> missing = new ArrayList<>();
        int lanes = 0;
        for (Element execution : children(surefire, "executions", "execution")) {
            lanes++;
            if (!strictIn(firstChild(execution, "configuration"))) {
                missing.add(text(firstChild(execution, "id")));
            }
        }
        assertTrue(lanes >= MIN_CORPUS_LANES, "Expected at least " + MIN_CORPUS_LANES
                + " surefire executions in corpus-eval/pom.xml, found " + lanes
                + "; the lane layout changed and this test no longer sees every lane.");
        assertTrue(missing.isEmpty(), "These corpus-eval lanes do not set " + PROPERTY + "=true in "
                + "their systemPropertyVariables: " + missing + ". A detector that throws during "
                + "analysis is then counted as silent, which is exactly how #605's crash left the "
                + "corpus green. Add <" + PROPERTY + ">true</" + PROPERTY + "> to each lane.");
    }

    @Test
    @DisplayName("the consumer fixtures fail on a detector that throws, in Maven and Gradle")
    void consumerFixturesAreStrict() {
        Path root = repoRoot();
        List<String> missing = new ArrayList<>();
        for (String pom : List.of("consumer-fixture/pom.xml", "consumer-fixture-langs/pom.xml")) {
            if (!strictIn(firstChild(surefirePlugin(parse(root.resolve(pom))), "configuration"))) {
                missing.add(pom);
            }
        }
        for (String gradle : List.of("consumer-fixture/build.gradle.kts",
                "consumer-fixture-langs/build.gradle.kts")) {
            if (!GRADLE_STRICT.matcher(read(root.resolve(gradle))).find()) {
                missing.add(gradle);
            }
        }
        assertTrue(missing.isEmpty(), "These consumer-fixture builds do not set " + PROPERTY
                + "=true for their tests: " + missing + ". Their fixtures assert that each "
                + "detector reports, and a detector that crashes before reporting must fail the "
                + "build rather than leave one stderr line.");
    }

    @Test
    @DisplayName("the examples fail on a detector that throws, in every CI run of the reactor")
    void examplesAreStrict() {
        Path root = repoRoot();
        assertTrue(GRADLE_STRICT.matcher(read(root.resolve("examples/build.gradle.kts"))).find(),
                "examples/build.gradle.kts does not set " + PROPERTY + "=true for its "
                        + "subprojects' tests. The examples intentionally demonstrate misuse, but "
                        + "a detector crash is never the demonstration.");

        String workflow = read(root.resolve(".github/workflows/e2e-tests.yml"));
        Matcher runs = EXAMPLES_MAVEN_RUN.matcher(workflow);
        List<String> runLines = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        while (runs.find()) {
            runLines.add(runs.group().trim());
            if (!runs.group().contains("-D" + PROPERTY + "=true")) {
                missing.add(runs.group().trim());
            }
        }
        assertFalse(runLines.isEmpty(), "No 'mvn ... -f examples/pom.xml' command found in "
                + "e2e-tests.yml; the workflow changed and this check no longer sees the examples "
                + "run.");
        assertTrue(missing.isEmpty(), "These examples runs in e2e-tests.yml do not pass -D"
                + PROPERTY + "=true: " + missing + ". The example poms are copy-paste material "
                + "for users and do not carry the switch, so the CI command has to. Surefire "
                + "forwards Maven user properties to the forked JVM.");
    }

    @Test
    @DisplayName("the disabled-demo audit stays lenient, because there a failing demo is the pass")
    void demoAuditDoesNotTurnACrashIntoAPass() {
        String demos = read(repoRoot().resolve(".github/workflows/example-demos.yml"));
        assertFalse(demos.contains(PROPERTY), "example-demos.yml sets " + PROPERTY + ". In that "
                + "audit a demonstration that fails is the expected outcome, so strict mode would "
                + "turn a detector crash into what looks like a demonstration that fired. Keep the "
                + "audit lenient and read crashes from its log instead.");
    }

    // ------------------------------------------------------------------------------------------

    private static boolean strictIn(Element configuration) {
        Element properties = firstChild(configuration, "systemPropertyVariables");
        return "true".equals(text(firstChild(properties, PROPERTY)));
    }

    private static Element surefirePlugin(Document pom) {
        NodeList plugins = pom.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            if ("maven-surefire-plugin".equals(text(firstChild(plugin, "artifactId")))) {
                return plugin;
            }
        }
        throw new AssertionError("No maven-surefire-plugin declared in " + pom.getDocumentURI());
    }

    private static List<Element> children(Element parent, String wrapper, String name) {
        List<Element> out = new ArrayList<>();
        Element holder = firstChild(parent, wrapper);
        if (holder == null) {
            return out;
        }
        for (Node n = holder.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getTagName())) {
                out.add(e);
            }
        }
        return out;
    }

    private static Element firstChild(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getTagName())) {
                return e;
            }
        }
        return null;
    }

    private static String text(Element element) {
        return element == null ? null : element.getTextContent().trim();
    }

    private static Document parse(Path pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(pom.toFile());
            document.setDocumentURI(pom.toString());
            return document;
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse " + pom, e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))
                    && Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not find the reactor root (a directory holding both pom.xml and "
                        + "settings.gradle.kts) above " + Path.of("").toAbsolutePath());
    }
}
