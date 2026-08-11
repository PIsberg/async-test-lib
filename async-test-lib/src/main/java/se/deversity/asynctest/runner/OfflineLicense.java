package se.deversity.asynctest.runner;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AISecure;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Properties;

/**
 * Verifier for {@code -Dlicense.file} offline license files: the sanctioned licensing path for
 * air-gapped and egress-blocked environments, where the online validators can never be reached.
 *
 * <p>A file is one line, {@code ATL1.<base64url(payload)>.<base64url(signature)>}. The payload is
 * UTF-8 {@code key=value} text ({@link Properties} syntax) carrying {@code product},
 * {@code licensee}, {@code email}, {@code binding} ({@code domain}, {@code exact} or
 * {@code none}; default {@code domain}), {@code expires} (ISO date, inclusive) and optionally
 * {@code issued} and {@code plan}. The signature is Ed25519 over the exact payload bytes,
 * verified against the vendor public key embedded below, so verification needs no network, no
 * provider account and no clock beyond the machine's own.
 *
 * <p>Every anomaly fails closed with a named reason: a presented-but-invalid credential is a
 * rejection, not an outage, and must never fall through to a grant. The deliberate limits are the
 * same as the rest of the gate's (see docs/LICENSING.md "the gate is not DRM"): a customer who
 * edits their clock or their JVM can bypass it, and the enforceable instrument is the license
 * agreement. What this class must guarantee is narrower and absolute: no file that the vendor's
 * private key did not sign, and no file outside its validity, may produce a grant.
 *
 * <p>Files are issued with {@code tools/IssueOfflineLicense.java}; the operator flow is in
 * docs/LICENSING.md Part 3.
 */
@AISecure(aspect = "authorization")
final class OfflineLicense {

    private static final String FORMAT_PREFIX = "ATL1";
    private static final String PRODUCT = "async-test-lib";

    /**
     * Vendor Ed25519 verification key (X.509 SubjectPublicKeyInfo, base64). The matching private
     * key lives only on the operator machine ({@code ~/.config/deversity/offline-license-signing/}).
     * Rotating it invalidates every file issued so far against builds carrying the new key, which
     * is why {@code IssueOfflineLicense keygen} refuses to overwrite an existing private key.
     */
    private static final String VENDOR_VERIFY_KEY_B64 =
        "MCowBQYDK2VwAyEADqzMqb40PGwHTxJ0zVGBgrOOjbZ0fBfvG45h0RrUO7E=";

    private static volatile @Nullable PublicKey verifyKeyOverride;

    private OfflineLicense() {}

    /** What a successful verification grants, for the once-per-JVM announce log line. */
    record Grant(String licensee, LocalDate expires) {}

    /**
     * Verifies the file at {@code path} and returns the grant, or throws {@link SecurityException}
     * naming exactly what failed. Never grants on any parse, signature, product, expiry or
     * binding anomaly.
     *
     * @param path      value of {@code -Dlicense.file}
     * @param userEmail value of {@code -Dlicense.user.email} ("" when unset); matched against the
     *                  licensed address according to the file's {@code binding}
     */
    static Grant verify(String path, String userEmail) {
        String content;
        try {
            content = Files.readString(Path.of(path), StandardCharsets.UTF_8).trim();
        } catch (IOException | InvalidPathException e) {
            throw new SecurityException("LICENSE MISCONFIGURED: license.file=" + path
                + " cannot be read (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")."
                + "\n  Check the path and that the file was copied intact."
                + "\n  To run without a licence while sorting it out: -Dlicense.mock.mode=true");
        }

        int firstDot = content.indexOf('.');
        int secondDot = firstDot < 0 ? -1 : content.indexOf('.', firstDot + 1);
        int extraDot = secondDot < 0 ? -1 : content.indexOf('.', secondDot + 1);
        if (firstDot < 0 || secondDot < 0 || extraDot >= 0
                || !FORMAT_PREFIX.equals(content.substring(0, firstDot))) {
            throw deny("OFFLINE_FILE_MALFORMED",
                "expected one line of the form " + FORMAT_PREFIX + ".<payload>.<signature>. "
                + "The file may have been truncated or re-encoded in transit; ask for it to be re-sent.");
        }

        byte[] payloadBytes;
        byte[] signature;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(content.substring(firstDot + 1, secondDot));
            signature = Base64.getUrlDecoder().decode(content.substring(secondDot + 1));
        } catch (IllegalArgumentException e) {
            throw deny("OFFLINE_FILE_MALFORMED",
                "payload or signature is not valid base64url. "
                + "The file may have been altered in transit; ask for it to be re-sent.");
        }

        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(verifyKey());
            sig.update(payloadBytes);
            if (!sig.verify(signature)) {
                throw deny("OFFLINE_FILE_SIGNATURE_INVALID",
                    "the signature does not match the payload, so the file was altered or was not "
                    + "issued by this vendor. Ask for it to be re-issued.");
            }
        } catch (GeneralSecurityException e) {
            throw deny("OFFLINE_FILE_SIGNATURE_INVALID",
                "signature verification failed (" + e.getClass().getSimpleName() + "). Ask for the "
                + "file to be re-issued.");
        }

        Properties payload = new Properties();
        try {
            payload.load(new StringReader(new String(payloadBytes, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw deny("OFFLINE_FILE_MALFORMED", "signed payload is unreadable: " + e.getMessage());
        }

        if (!PRODUCT.equals(payload.getProperty("product"))) {
            throw deny("OFFLINE_FILE_WRONG_PRODUCT",
                "this file licenses '" + payload.getProperty("product") + "', not " + PRODUCT + ".");
        }
        String licensee = payload.getProperty("licensee", "").trim();
        String expiresRaw = payload.getProperty("expires", "");
        if (licensee.isEmpty() || expiresRaw.isEmpty()) {
            throw deny("OFFLINE_FILE_MALFORMED", "signed payload is missing licensee or expires.");
        }

        LocalDate expires;
        try {
            expires = LocalDate.parse(expiresRaw);
        } catch (DateTimeParseException e) {
            throw deny("OFFLINE_FILE_MALFORMED", "expires is not an ISO date: " + expiresRaw);
        }
        if (LocalDate.now(ZoneOffset.UTC).isAfter(expires)) {
            throw deny("OFFLINE_LICENSE_EXPIRED",
                "the licence expired on " + expires + ". Renewal issues a new file; nothing else in "
                + "your build configuration changes.");
        }

        checkBinding(payload, userEmail);
        return new Grant(licensee, expires);
    }

    /**
     * Enforces the file's email binding, mirroring the online providers' scoping: {@code domain}
     * covers everyone on the licensed address's domain (the default, and what a team purchase
     * means), {@code exact} narrows to the one address, {@code none} skips the check for
     * negotiated site licences.
     */
    private static void checkBinding(Properties payload, String userEmail) {
        String binding = payload.getProperty("binding", "domain").trim();
        if ("none".equals(binding)) {
            return;
        }
        String licensed = payload.getProperty("email", "").trim();
        if (licensed.isEmpty()) {
            throw deny("OFFLINE_FILE_MALFORMED", "binding=" + binding + " but the payload has no email.");
        }
        if (userEmail == null || userEmail.isBlank()) {
            throw deny("OFFLINE_LICENSE_SCOPE_MISMATCH",
                "the file is bound to " + (binding.equals("exact") ? licensed : "the " + domainOf(licensed) + " domain")
                + " but -Dlicense.user.email is not set. Set it to the address your builds run as.");
        }
        boolean covered = switch (binding) {
            case "exact" -> licensed.equalsIgnoreCase(userEmail.trim());
            case "domain" -> {
                String licensedDomain = domainOf(licensed);
                String userDomain = domainOf(userEmail.trim());
                yield licensedDomain != null && licensedDomain.equalsIgnoreCase(userDomain);
            }
            default -> throw deny("OFFLINE_FILE_MALFORMED", "unknown binding '" + binding + "'.");
        };
        if (!covered) {
            throw deny("OFFLINE_LICENSE_SCOPE_MISMATCH",
                "license.user.email=" + userEmail + " is not covered: the file is bound to "
                + (binding.equals("exact") ? "exactly " + licensed : "the " + domainOf(licensed) + " domain")
                + ". Ask for the licence to be re-scoped if this address should be covered.");
        }
    }

    private static @Nullable String domainOf(@Nullable String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        return email == null || at <= 0 || at == email.length() - 1 ? null : email.substring(at + 1);
    }

    /**
     * {@return the denial, so callers can {@code throw deny(...)} and the compiler sees the exit}
     *
     * @param code   stable machine-greppable reason code
     * @param detail human explanation with the next step
     */
    private static SecurityException deny(String code, String detail) {
        return new SecurityException("LICENSE DENIED: " + code + " - " + detail);
    }

    private static PublicKey verifyKey() throws GeneralSecurityException {
        PublicKey override = verifyKeyOverride;
        if (override != null) {
            return override;
        }
        byte[] spki = Base64.getDecoder().decode(VENDOR_VERIFY_KEY_B64);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
    }

    /**
     * Test-only: substitute the verification key so tests can sign with an ephemeral keypair.
     * Deliberately package-private; the production key is a compile-time constant for everyone
     * else, and overriding it at runtime must never become part of the supported surface.
     */
    static void overrideVerifyKeyForTesting(@Nullable PublicKey key) {
        verifyKeyOverride = key;
    }
}
