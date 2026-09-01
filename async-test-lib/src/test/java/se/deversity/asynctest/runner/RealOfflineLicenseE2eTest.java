package se.deversity.asynctest.runner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.E2E;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end proof of the offline licensing path with the <em>real</em> Deversity AB file and
 * the real vendor key embedded in the library - no test-only key override, no stub. The
 * hermetic {@link OfflineLicenseGuardTest} proves the verification logic with an ephemeral
 * keypair; this class proves the actual issued artifact and the actual embedded public key
 * agree, which is the property a customer's build depends on.
 *
 * <p><b>Where it runs.</b> Only where {@code ~/.config/deversity/e2e-license.env} has been
 * sourced (the operator machine): each test assumes {@code ATL_E2E_LICENSE_FILE} points at the
 * issued file and skips cleanly elsewhere. Run it with:
 *
 * <pre>{@code
 * set -a; . ~/.config/deversity/e2e-license.env; set +a
 * mvn -pl async-test-lib test -Dtest=RealOfflineLicenseE2eTest -DfailIfNoTests=false -P e2e
 * }</pre>
 *
 * <p>No network is involved on any path here; strict network mode is still set so a defect
 * that fell through to online validation would fail loudly instead of riding grace. The three
 * directions pinned: the domain binding covers a colleague address that is not the licensed
 * one, a foreign domain is denied, and a tampered copy of the real file is denied by the real
 * signature check.
 *
 * <p>The file expires yearly (renewal note in the env file); when it lapses this test fails
 * with {@code OFFLINE_LICENSE_EXPIRED}, which is itself the correct end-to-end signal.
 */
@E2E
class RealOfflineLicenseE2eTest {

    private final Map<String, String> savedProps = new HashMap<>();

    private String licenseFile;

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() {
        licenseFile = System.getenv("ATL_E2E_LICENSE_FILE");
        assumeTrue(licenseFile != null && !licenseFile.isBlank() && Files.isRegularFile(Path.of(licenseFile)),
            "operator-machine test: source ~/.config/deversity/e2e-license.env to run it");

        resetCache();
        // Surefire sets license.mock.mode=true for the whole build; this test exists to
        // exercise the gate for real.
        set("license.mock.mode", "false");
        set("license.provider", null);
        set("license.key", null);
        set("license.file", licenseFile);
        set("license.user.email", "e2e-colleague@deversity.se");
        set("license.network.mode", "strict");
        set("license.cache.ttl.hours", "-1");
    }

    @AfterEach
    void tearDown() {
        savedProps.forEach((k, v) -> {
            if (v == null) System.clearProperty(k);
            else System.setProperty(k, v);
        });
        savedProps.clear();
        resetCache();
    }

    @Test
    void theRealFileCoversEveryAddressOnTheLicensedDomain() {
        assertDoesNotThrow(() -> LicenseGuard.check(config()),
            "The issued file is domain-bound, so an address that is not the licensed one but "
            + "shares its domain must be covered - that is what a company purchase means. A "
            + "failure here means the issued file and the embedded vendor key disagree");
    }

    @Test
    void aForeignDomainIsDenied() {
        set("license.user.email", "mallory@evil-corp.com");

        SecurityException ex = assertThrows(SecurityException.class,
            () -> LicenseGuard.check(config()));
        assertTrue(ex.getMessage().contains("OFFLINE_LICENSE_SCOPE_MISMATCH"), ex.getMessage());
    }

    @Test
    void aTamperedCopyOfTheRealFileIsDenied() throws IOException {
        String content = Files.readString(Path.of(licenseFile), StandardCharsets.UTF_8).trim();
        int firstDot = content.indexOf('.');
        int secondDot = content.indexOf('.', firstDot + 1);
        // Flip one character inside the signed payload; the signature stays the original.
        int i = firstDot + 1 + (secondDot - firstDot) / 2;
        char original = content.charAt(i);
        char flipped = original == 'A' ? 'B' : 'A';
        String tampered = content.substring(0, i) + flipped + content.substring(i + 1);
        Path copy = tmp.resolve("tampered.atl-license");
        Files.writeString(copy, tampered + "\n", StandardCharsets.UTF_8);
        set("license.file", copy.toString());

        SecurityException ex = assertThrows(SecurityException.class,
            () -> LicenseGuard.check(config()),
            "A modified payload against the original signature must never grant");
        assertTrue(ex.getMessage().contains("OFFLINE_FILE_"),
            "Tampering must surface as a named OFFLINE_FILE_* rejection (signature-invalid or "
            + "malformed, depending on where the flip lands in the base64): " + ex.getMessage());
    }

    private static AsyncTestConfig config() {
        return AsyncTestConfig.builder().licenseMockMode(false).build();
    }

    private void set(String key, String value) {
        // Not Map.putIfAbsent: it treats a null-valued mapping as absent, so a second set() of
        // a key that started unset would overwrite the saved baseline with the mid-test value,
        // and @AfterEach would "restore" the leak into every later test class in this JVM.
        if (!savedProps.containsKey(key)) {
            savedProps.put(key, System.getProperty(key));
        }
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static void resetCache() {
        try {
            Method m = LicenseGuard.class.getDeclaredMethod("resetForTesting");
            m.setAccessible(true);
            m.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
