package se.deversity.asynctest.example.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Derives per-session keys from a master secret — modeling the JDK 25
 * {@code javax.crypto.KDF} API (JEP 510, e.g. HKDF-SHA256).
 *
 * <p>The real {@code KDF} type only exists on JDK 24+; this example targets the
 * Java 21 baseline, so the derivation object here is a small HKDF-extract-like
 * construction over {@link MessageDigest} that reproduces the property that
 * matters: <strong>mutable per-operation state inside the derivation object</strong>.
 * The KDF javadoc states: "Unless otherwise documented by an implementation, the
 * methods defined in this class are not thread-safe."
 *
 * <p>{@link #deriveSessionKey(String)} threads that state through
 * {@code reset → update(master) → update(context) → digest}; two threads
 * interleaving those steps on ONE shared instance fold each other's context
 * bytes into the same digest and silently derive wrong keys.
 */
public final class SessionKeyService {

    /** BUG: one derivation object (models one javax.crypto.KDF) shared by all threads. */
    private final MessageDigest sharedDigest;

    private final byte[] masterSecret;

    public SessionKeyService(byte[] masterSecret) throws NoSuchAlgorithmException {
        this.sharedDigest = MessageDigest.getInstance("SHA-256");
        this.masterSecret = masterSecret.clone();
    }

    /**
     * Models {@code kdf.deriveData(...)} on a shared KDF instance — NOT thread-safe.
     */
    public byte[] deriveSessionKey(String sessionContext) {
        sharedDigest.reset();
        sharedDigest.update(masterSecret);
        sharedDigest.update(sessionContext.getBytes(StandardCharsets.UTF_8));
        return sharedDigest.digest();
    }

    /**
     * The fix: a fresh derivation object per call (KDF.getInstance is cheap;
     * a ThreadLocal works too).
     */
    public byte[] deriveSessionKeySafely(String sessionContext) throws NoSuchAlgorithmException {
        MessageDigest local = MessageDigest.getInstance("SHA-256");
        local.update(masterSecret);
        local.update(sessionContext.getBytes(StandardCharsets.UTF_8));
        return local.digest();
    }
}
