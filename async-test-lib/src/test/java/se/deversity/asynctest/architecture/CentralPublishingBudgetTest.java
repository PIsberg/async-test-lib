package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps a release inside Maven Central's monthly file budget (#1391).
 *
 * <p>Central limits an organization to a number of releases and files a month, shared by every
 * project of the organization, and 1.14.4 was refused once the limit was reached. Two plugin
 * defaults doubled what a release sent: {@code central-publishing-maven-plugin} adds SHA-256 and
 * SHA-512 beside the MD5 and SHA-1 Central requires, and CycloneDX attached the SBOM in two formats.
 * A plugin upgrade or a copied configuration that brings either back fails here.
 */
class CentralPublishingBudgetTest {

    private static final String CENTRAL = "central-publishing-maven-plugin";
    private static final String CYCLONEDX = "cyclonedx-maven-plugin";

    @Test
    @DisplayName("every Central publishing configuration sends only the checksums Central requires")
    void centralGetsOnlyTheRequiredChecksums() throws Exception {
        for (String pom : List.of("pom.xml", "async-test-maven-plugin/pom.xml")) {
            List<Element> plugins = plugins(pom, CENTRAL);
            assertFalse(plugins.isEmpty(), pom + " declares no " + CENTRAL);
            for (Element plugin : plugins) {
                assertEquals("required", text(child(plugin, "configuration"), "checksums"),
                        pom + ": " + CENTRAL + " must set <checksums>required</checksums>; its default"
                                + " also uploads .sha256 and .sha512 for every file, which Central does not"
                                + " require and counts against the monthly file limit");
            }
        }
    }

    @Test
    @DisplayName("the SBOM is attached to the deployment as JSON only, and the XML is still generated")
    void theSbomIsAttachedOnceAndStillWrittenInBothFormats() throws Exception {
        List<Element> plugins = plugins("pom.xml", CYCLONEDX);
        assertEquals(1, plugins.size(), "one " + CYCLONEDX + " in the reactor pom");
        Element plugin = plugins.get(0);
        String shared = text(child(plugin, "configuration"), "outputFormat");

        List<String> attached = new ArrayList<>();
        boolean xmlWritten = false;
        for (Element execution : children(child(plugin, "executions"), "execution")) {
            Element configuration = child(execution, "configuration");
            String format = configuration == null || text(configuration, "outputFormat") == null
                    ? shared : text(configuration, "outputFormat");
            boolean skipAttach = configuration != null && "true".equals(text(configuration, "skipAttach"));
            if (skipAttach) {
                xmlWritten |= "xml".equals(format);
            } else {
                attached.add(format);
            }
        }
        assertEquals(List.of("json"), attached,
                "the deployment gets one SBOM, the JSON that Dependency-Track, grype and trivy read;"
                        + " 'all' uploads both formats, each with its signature and checksums");
        assertTrue(xmlWritten, "an execution with skipAttach still writes target/bom.xml, which"
                + " publish.yml signs and attaches to the GitHub release and verify-sbom.sh requires");
    }

    @Test
    @DisplayName("each workflow that generates the SBOM by goal also runs the XML execution")
    void workflowsInvokingTheGoalAlsoWriteTheXml() throws Exception {
        for (String workflow : List.of(".github/workflows/sbom.yml", ".github/workflows/publish.yml")) {
            String text = Files.readString(repoRoot().resolve(workflow));
            assertTrue(text.contains("makeAggregateBom@cyclonedx-xml"),
                    workflow + " calls the goal directly, which runs only the plugin-level (JSON)"
                            + " configuration; without makeAggregateBom@cyclonedx-xml there is no"
                            + " target/bom.xml for verify-sbom.sh and the GitHub release");
        }
    }

    private static List<Element> plugins(String pom, String artifactId) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(repoRoot().resolve(pom).toFile());
        List<Element> found = new ArrayList<>();
        NodeList all = document.getElementsByTagName("plugin");
        for (int i = 0; i < all.getLength(); i++) {
            Element plugin = (Element) all.item(i);
            if (artifactId.equals(text(plugin, "artifactId"))) {
                found.add(plugin);
            }
        }
        return found;
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        if (parent != null) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && name.equals(element.getTagName())) {
                    found.add(element);
                }
            }
        }
        return found;
    }

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? null : element.getTextContent().trim();
    }

    /** Walks up from the working directory to the reactor root; see BuildMetadataSyncTest. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts")) && Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not find the reactor root above " + Path.of("").toAbsolutePath());
    }
}
