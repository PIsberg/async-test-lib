package se.deversity.asynctest.runner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.asynctest.AsyncTestConfig;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@code -Dlicense.file} end-to-end through {@link LicenseGuard}: the offline licensing
 * path for air-gapped and egress-blocked environments. Files are signed with an ephemeral
 * Ed25519 keypair swapped in via the package-private test hook, so these tests prove the
 * verification logic, not the production key.
 *
 * <p>The security property pinned throughout: a present-but-invalid file is a rejection and must
 * fail closed with a named reason. It must never fall through to online validation, to CI
 * auto-mock, or to a grant. The one thing that outranks it is the explicit, documented
 * {@code -Dlicense.mock.mode=true} bypass.
 */
class OfflineLicenseGuardTest {

    private static KeyPair keyPair;

    private final Map<String, String> savedProps = new HashMap<>();

    @TempDir
    Path dir;

    @BeforeAll
    static void generateKeys() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        resetCache();
        OfflineLicense.overrideVerifyKeyForTesting(keyPair.getPublic());

        // Surefire sets license.mock.mode=true for the whole build; these tests exercise the gate.
        set("license.mock.mode", "false");
        set("license.user.email", "alice@acme-corp.com");
        set("license.key", null);
        set("license.provider", null);
        set("license.network.mode", null);
        set("keygen.base.uri", null);
        set("license.cache.dir", dir.resolve("cache").toString());
    }

    @AfterEach
    void tearDown() {
        OfflineLicense.overrideVerifyKeyForTesting(null);
        savedProps.forEach((k, v) -> {
            if (v == null) System.clearProperty(k);
            else System.setProperty(k, v);
        });
        resetCache();
    }

    private void set(String key, String value) {
        savedProps.putIfAbsent(key, System.getProperty(key));
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static AsyncTestConfig config() {
        return AsyncTestConfig.builder().licenseMockMode(false).build();
    }

    // ---- file construction helpers -------------------------------------------------------

    private static String payload(String product, String licensee, String email, String binding,
                                  LocalDate expires) {
        StringBuilder sb = new StringBuilder();
        sb.append("product=").append(product).append('\n');
        sb.append("licensee=").append(licensee).append('\n');
        if (email != null) sb.append("email=").append(email).append('\n');
        if (binding != null) sb.append("binding=").append(binding).append('\n');
        sb.append("issued=").append(LocalDate.now(ZoneOffset.UTC)).append('\n');
        sb.append("expires=").append(expires).append('\n');
        return sb.toString();
    }

    private static byte[] sign(byte[] payloadBytes) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(keyPair.getPrivate());
        s.update(payloadBytes);
        return s.sign();
    }

    private Path write(byte[] payloadBytes, byte[] signature) throws IOException {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        Path f = dir.resolve("test.atl-license");
        Files.writeString(f,
            "ATL1." + b64.encodeToString(payloadBytes) + "." + b64.encodeToString(signature) + "\n",
            StandardCharsets.UTF_8);
        return f;
    }

    /** A correctly signed file for the given payload fields. */
    private Path issue(String product, String licensee, String email, String binding,
                       LocalDate expires) throws Exception {
        byte[] payloadBytes = payload(product, licensee, email, binding, expires)
            .getBytes(StandardCharsets.UTF_8);
        return write(payloadBytes, sign(payloadBytes));
    }

    // ---- grants --------------------------------------------------------------------------

    @Test
    void domainBoundFile_coversEveryAddressOnTheLicensedDomain() throws Exception {
        Path file = issue("async-test-lib", "Acme Corp AB", "licence@acme-corp.com", "domain",
            LocalDate.now(ZoneOffset.UTC).plusYears(1));
        set("license.file", file.toString());
        set("license.user.email", "alice@acme-corp.com");

        assertDoesNotThrow(() -> LicenseGuard.check(config()),
            "A domain-bound file bought once must cover every developer on the domain, "
            + "matching the online LemonSqueezy semantics");
    }

    @Test
    void offlineFile_needsNoNetworkAndNoProviderCoordinates() throws Exception {
        Path file = issue("async-test-lib", "Acme Corp AB", "licence@acme-corp.com", "domain",
            LocalDate.now(ZoneOffset.UTC).plusYears(1));
        set("license.file", file.toString());
        // A key with placeholder provider coordinates would fail requireProviderCoordinates if
        // the online path ran; a passing check therefore proves the file short-circuits it.
        set("license.key", "SOME-KEY-THE-FILE-OUTRANKS");

        assertDoesNotThrow(() -> LicenseGuard.check(config()),
            "license.file is the whole decision: no provider coordinates, no network, "
            + "and any configured online key is not consulted");
    }

    @Test
    void bindingNone_grantsWithoutAnyUserEmail() throws Exception {
        Path file = issue("async-test-lib", "Acme Corp AB", null, "none",
            LocalDate.now(ZoneOffset.UTC).plusYears(1));
        set("license.file", file.toString());
        set("license.user.email", null);

        assertDoesNotThrow(() -> LicenseGuard.check(config()),
            "binding=none is the negotiated site-licence shape and asks nothing of the runner");
    }

    @Test
    void explicitMockMode_outranksABrokenFile() throws Exception {
        Path file = dir.resolve("garbage.atl-license");
        Files.writeString(file, "not a license at all", StandardCharsets.UTF_8);
        set("license.file", file.toString());

        AsyncTestConfig cfg = AsyncTestConfig.builder().licenseMockMode(true).build();
        assertDoesNotThrow(() -> LicenseGuard.check(cfg),
            "-Dlicense.mock.mode=true stays the documented escape hatch regardless of what "
            + "license.file points at");
    }

    // ---- rejections ----------------------------------------------------------------------

    @Test
    void exactBoundFile_rejectsAColleagueOnTheSameDomain() throws Exception {
        Path file = issue("async-test-lib", "Acme Corp AB", "licence@acme-corp.com", "exact",
            LocalDate.now(ZoneOffset.UTC).plusYears(1));
        set("license.file", file.toString());
        set("license.user.email", "alice@acme-corp.com");

        SecurityException ex = assertThrows(SecurityException.class, () -> LicenseGuard.check(config()));
        assertTrue(ex.getMessage().contains("OFFLINE_LICENSE_SCOPE_MISMATCH"), ex.getMessage());
    }

    @Test
    void expiredFile_denies() throws Exception {
        Path file = issue("async-test-lib", "Acme Corp AB", "licence@acme-corp.com", "domain",
            LocalDate.now(ZoneOffset.UTC).minusDays(1));
        set("license.file", file.toString());

        SecurityException ex = assertThrows(SecurityException.class, () -> LicenseGuard.check(config()));
        assertTrue(ex.getMessage().contains("OFFLINE_LICENSE_EXPIRED"), ex.getMessage());
    }

    @Test
    void payloadNotMatchingTheSignature_denies() throws Exception {
        // Sign one payload, present another: the classic tamper. The signature itself is real.
        byte[] signedPayload = payload("async-test-lib", "Acme Corp AB", "licence@acme-corp.com",
            "domain", LocalDate.now(ZoneOffset.UTC).plusYears(1)).getBytes(StandardCharsets.UTF_8);
        byte[] presentedPayload = payload("async-test-lib", "Acme Corp AB", "licence@evil-corp.com",
            "domain", LocalDate.now(ZoneOffset.UTC).plusYears(1)).getBytes(StandardCharsets.UTF_8);
        Path file = write(presentedPayload, sign(signedPayload));
        set("license.file", file.toString());
        set("license.user.email", "mallory@evil-corp.com");

        SecurityException ex = assertThrows(SecurityException.class, () -> LicenseGuard.check(config()));
        assertTrue(ex.getMessage().contains("OFFLINE_FILE_SIGNATURE_INVALID"), ex.getMessage());
    }

    @Test
    void fileForAnotherProduct_denies() throws Exception {
        Path file = issue("some-other-lib", "Acme Corp AB", "licence@acme-corp.com", "domain",
            LocalDate.now(ZoneOffset.UTC).plusYears(1));
        set("license.file", file.toString());

        SecurityException ex = assertThrows(SecurityException.class, () -> LicenseGuard.check(config()));
        assertTrue(ex.getMessage().contains("OFFLINE_FILE_WRONG_PRODUCT"), ex.getMessage());
    }

    @Test
    void garbageFile_deniesAsMalformed() throws Exception {
        Path file = dir.resolve("garbage.atl-license");
        Files.writeString(file, "certainly.not$valid", StandardCharsets.UTF_8);
        set("license.file", file.toString());

        SecurityException ex = assertThrows(SecurityException.class, () -> LicenseGuard.check(config()));
        assertTrue(ex.getMessage().contains("OFFLINE_FILE_MALFORMED"), ex.getMessage());
    }

    @Test
    void missingFile_deniesAsMisconfigurationNamingThePath() {
        Path missing = dir.resolve("does-not-exist.atl-license");
        set("license.file", missing.toString());

        SecurityException ex = assertThrows(SecurityException.class, () -> LicenseGuard.check(config()));
        assertTrue(ex.getMessage().contains("LICENSE MISCONFIGURED"), ex.getMessage());
        assertTrue(ex.getMessage().contains(missing.toString()), ex.getMessage());
    }

    @Test
    void domainBoundFile_withoutUserEmail_deniesAndNamesTheProperty() throws Exception {
        Path file = issue("async-test-lib", "Acme Corp AB", "licence@acme-corp.com", "domain",
            LocalDate.now(ZoneOffset.UTC).plusYears(1));
        set("license.file", file.toString());
        set("license.user.email", null);

        SecurityException ex = assertThrows(SecurityException.class, () -> LicenseGuard.check(config()));
        assertTrue(ex.getMessage().contains("license.user.email"), ex.getMessage());
    }

    @Test
    void aBadFileFailsClosedEvenWhereCiWouldAutoMock() throws Exception {
        // The auto-mock branch requires "no credentials asserted". A license.file is a credential,
        // so this denial must hold both locally and on a CI runner; asserted unconditionally on
        // purpose, unlike the environment-dependent no-key path in LicenseGuardTest.
        Path file = issue("async-test-lib", "Acme Corp AB", "licence@acme-corp.com", "domain",
            LocalDate.now(ZoneOffset.UTC).minusDays(30));
        set("license.file", file.toString());

        assertThrows(SecurityException.class, () -> LicenseGuard.check(config()),
            "An expired offline file must deny in every environment, including CI");
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
