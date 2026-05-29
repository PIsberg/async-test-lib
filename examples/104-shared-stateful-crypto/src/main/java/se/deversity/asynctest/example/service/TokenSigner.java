package se.deversity.asynctest.example.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Signs tokens with an HMAC-SHA256 {@link Mac}.
 *
 * <p><strong>Bug:</strong> A single {@link Mac} instance is created once and stored in a
 * field, then shared across all threads. {@link Mac} is stateful — a signing operation is
 * {@code init} &rarr; {@code update} &rarr; {@code doFinal}, and the intermediate bytes
 * accumulate inside the instance. When two threads call {@link #sign(String)} concurrently
 * on the same {@code Mac}, their input bytes interleave into one shared buffer, producing a
 * MAC that verifies for neither caller's message (and may throw {@code IllegalStateException}).
 *
 * <p><strong>Fix:</strong> Never share a stateful {@code Mac} across threads. Use a
 * {@code ThreadLocal<Mac>} so each thread owns its own instance, or create a fresh
 * {@code Mac} (and {@code init} it) per {@link #sign(String)} call.
 */
public class TokenSigner {

    // BUG: Mac is stateful and not thread-safe — do not share across threads
    private final Mac mac;

    public TokenSigner(byte[] keyBytes) {
        try {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize HmacSHA256 Mac", e);
        }
    }

    /**
     * Signs a message with the shared {@link Mac}. Not thread-safe.
     *
     * <p>Note: this intentionally does not re-init the {@code Mac} per call, so the shared
     * mutable state of the single instance is exercised directly.
     */
    public byte[] sign(String message) {
        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the shared {@link Mac} instance for test instrumentation. */
    public Mac getMac() {
        return mac;
    }
}
