package se.deversity.asynctest.example.service;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

/**
 * Computes an integrity checksum for each payload before it is stored.
 *
 * <p>A {@link Checksum} is an accumulator. {@code update()} folds bytes into a running
 * value, {@code getValue()} reads it, {@code reset()} clears it — and none of the three is
 * synchronized. The interface's contract is inherently stateful, so "is CRC32 thread-safe?"
 * has the same answer as "is a counter thread-safe?": not for read-modify-write.
 *
 * <p>What makes this one nastier than most is the failure mode. There is no exception, no
 * corruption anybody can point at — just a number. Two threads updating one CRC32 produce a
 * checksum over the concatenation of both payloads, and it is a perfectly well-formed
 * checksum. It simply does not match either payload, and you find out when a downstream
 * integrity check rejects a file that was never damaged.
 */
public final class PayloadIntegrityService {

    /** BUG: one accumulator for every payload from every thread. */
    private final CRC32 sharedChecksum = new CRC32();

    /**
     * Checksums {@code payload} using the shared accumulator.
     *
     * <p>reset → update → getValue is a three-step read-modify-write. A second thread
     * entering at any point folds its bytes into this thread's value.
     */
    public long checksum(String payload) {
        sharedChecksum.reset();
        sharedChecksum.update(payload.getBytes(StandardCharsets.UTF_8));
        return sharedChecksum.getValue();
    }

    /** The fix: an accumulator per call. A CRC32 is a long and a table lookup. */
    public long checksumSafely(String payload) {
        CRC32 local = new CRC32();
        local.update(payload.getBytes(StandardCharsets.UTF_8));
        return local.getValue();
    }
}
