package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every release carries build provenance, attached where Scorecard looks for it (#961).
 *
 * <p>Releases were GPG- and cosign-signed but had no provenance, so Signed-Releases scored 8 with
 * "release artifact v1.12.x does not have provenance" for each of the last five. Scorecard's
 * {@code releasesHaveProvenance} probe counts only a release asset whose name ends in
 * {@code .intoto.jsonl} ({@code provenanceExtensions} in its source), so {@code publish.yml}
 * attests the artifacts with {@code actions/attest-build-provenance} and attaches the bundle under
 * that suffix. Only a tag push runs it; this pins the wiring.
 */
class PublishAttachesProvenanceTest {

    @Test
    @DisplayName("publish.yml attests provenance and attaches it as an .intoto.jsonl release asset")
    void publishAttachesProvenance() throws IOException {
        String publish = Files.readString(repoRoot().resolve(".github/workflows/publish.yml"),
                StandardCharsets.UTF_8);
        assertTrue(publish.contains("uses: actions/attest-build-provenance@"),
                "publish.yml does not generate build provenance (#961)");
        assertTrue(publish.contains("attestations: write"),
                "attest-build-provenance needs the attestations: write permission");
        int attest = publish.indexOf("actions/attest-build-provenance@");
        int release = publish.indexOf("gh release create");
        int asset = publish.indexOf(".intoto.jsonl", attest);
        assertTrue(attest >= 0 && release > attest && asset > attest && asset < release,
                "the provenance must be attested before the release is created and attached to it"
                        + " as an .intoto.jsonl asset, the only suffix Scorecard's probe counts");
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
        throw new IllegalStateException("Could not find the reactor root above "
                + Path.of("").toAbsolutePath());
    }
}
