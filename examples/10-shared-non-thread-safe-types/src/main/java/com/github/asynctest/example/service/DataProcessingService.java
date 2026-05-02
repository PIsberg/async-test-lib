package com.github.asynctest.example.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transaction-record processor that validates, formats, and fingerprints payment data.
 *
 * This is plausible production code found in payment-gateway or audit-log services.
 *
 * ========================================================================
 * DETECTED BY: SharedMatcherDetector, SharedDecimalFormatDetector,
 *              SharedMessageDigestDetector
 * ========================================================================
 *
 * THE THREE BUGS:
 *
 * 1. Shared Matcher — SILENT WRONG RESULTS
 *    Pattern is thread-safe, but Matcher holds per-match position and group state.
 *    Concurrent resets corrupt the match cursor; validate() returns wrong answers
 *    with no exception thrown.
 *    Fix: call PATTERN.matcher(id) inside each method call.
 *
 * 2. Shared DecimalFormat — GARBLED OUTPUT
 *    DecimalFormat is not thread-safe. Concurrent format() calls corrupt the
 *    internal multiplier and grouping state, producing strings like "1,2345.6"
 *    or throwing ArrayIndexOutOfBoundsException.
 *    Fix: ThreadLocal<DecimalFormat> or new DecimalFormat("#,##0.00") per call.
 *
 * 3. Shared MessageDigest — SILENT WRONG HASHES
 *    MessageDigest mutates internal digest state (running buffer, byte count, padding)
 *    on every update()/digest() call. Concurrent calls silently return wrong hashes
 *    with no exception — the hardest of the three bugs to diagnose in production.
 *    Fix: MessageDigest.getInstance("SHA-256") per thread or ThreadLocal.
 *
 * Under single-threaded @Test these bugs are invisible: operations serialize
 * naturally and results are always correct. Under concurrent @AsyncTest multiple
 * threads collide on the shared state simultaneously, exposing all three bugs.
 */
public class DataProcessingService {

    private static final Pattern TX_ID_PATTERN =
            Pattern.compile("^TX-[0-9]{6}-[A-Z]{3}$");

    // BUG 1: Matcher is not thread-safe — this field must not be shared
    private final Matcher txIdMatcher = TX_ID_PATTERN.matcher("");

    // BUG 2: DecimalFormat is not thread-safe — this field must not be shared
    private final DecimalFormat amountFormat = new DecimalFormat("#,##0.00");

    // BUG 3: MessageDigest is not thread-safe — this field must not be shared
    private final MessageDigest sha256;

    public DataProcessingService() {
        try {
            this.sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Returns true when the transaction ID matches the required format.
     *
     * BUG: txIdMatcher.reset() + find() on a shared Matcher are not atomic.
     * Concurrent threads interleave reset and find, reading each other's cursor,
     * producing mismatched validation results.
     */
    public boolean validateTransactionId(String txId) {
        // BUG: shared Matcher — reset() and find() are not atomic across threads
        txIdMatcher.reset(txId);
        return txIdMatcher.matches();
    }

    /**
     * Formats a monetary amount using the shared DecimalFormat.
     *
     * BUG: DecimalFormat.format() mutates internal state. Concurrent calls
     * produce garbled strings or throw ArrayIndexOutOfBoundsException.
     */
    public String formatAmount(double amount) {
        // BUG: shared DecimalFormat — format() mutates internal digit buffer
        return amountFormat.format(amount);
    }

    /**
     * Returns the SHA-256 fingerprint of a transaction ID.
     *
     * BUG: MessageDigest.update() and digest() mutate internal state.
     * Concurrent calls silently produce wrong hashes — no exception is thrown.
     */
    public String fingerprint(String txId) {
        // BUG: shared MessageDigest — update() and digest() corrupt shared state
        sha256.update(txId.getBytes());
        return HexFormat.of().formatHex(sha256.digest());
    }

    // -------------------------------------------------------------------------
    // Accessors for the shared (buggy) objects — used by tests to pass them to
    // the Phase 11 detectors so the detector can track which instance is shared.
    // -------------------------------------------------------------------------

    public Matcher       getBuggyMatcher()       { return txIdMatcher; }
    public DecimalFormat getBuggyAmountFormat()   { return amountFormat; }
    public MessageDigest getBuggyMessageDigest()  { return sha256; }

    // -------------------------------------------------------------------------
    // Thread-safe versions (the fix)
    // -------------------------------------------------------------------------

    /** Fixed validateTransactionId: creates a new Matcher per call. */
    public boolean validateTransactionIdFixed(String txId) {
        return TX_ID_PATTERN.matcher(txId).matches();
    }

    private static final ThreadLocal<DecimalFormat> AMOUNT_FMT =
            ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.00"));

    /** Fixed formatAmount: uses a ThreadLocal DecimalFormat. */
    public String formatAmountFixed(double amount) {
        return AMOUNT_FMT.get().format(amount);
    }

    /** Fixed fingerprint: creates a new MessageDigest per call. */
    public String fingerprintFixed(String txId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(txId.getBytes());
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
