package se.deversity.asynctest.example.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Frames outbound protocol messages into a scratch buffer before they go on the wire.
 *
 * <p>A {@link ByteBuffer} is not a byte array — it is a cursor. {@code position},
 * {@code limit} and {@code mark} are mutable fields, and every relative
 * {@code put}/{@code get}, {@code flip()}, {@code clear()} and {@code rewind()} moves them.
 * The class carries no synchronization and its javadoc says so: buffers "are not safe for
 * use by multiple concurrent threads".
 *
 * <p>{@link #frame(String)} runs {@code clear → putInt(length) → put(payload) → flip → get}
 * on ONE shared buffer. Two threads interleaving those five steps produce a frame whose
 * length header belongs to one message and whose body belongs to another, or trip
 * {@link java.nio.BufferOverflowException} / {@link java.nio.BufferUnderflowException} when
 * one thread's {@code flip()} lands between another's {@code put} calls.
 */
public final class MessageFramingService {

    /** BUG: one scratch buffer for every thread that frames a message. */
    private final ByteBuffer sharedScratch = ByteBuffer.allocate(1024);

    /**
     * Frames {@code payload} as {@code [int length][utf-8 bytes]}.
     *
     * <p>Every call mutates the shared cursor. Correct single-threaded, wrong the moment a
     * second thread enters.
     */
    public byte[] frame(String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);

        sharedScratch.clear();              // position = 0, limit = capacity
        sharedScratch.putInt(body.length);  // relative put: advances position
        sharedScratch.put(body);            // relative put: advances position
        sharedScratch.flip();               // limit = position, position = 0

        byte[] out = new byte[sharedScratch.remaining()];
        sharedScratch.get(out);             // relative get: advances position
        return out;
    }

    /**
     * The fix: a buffer per call. Allocation is cheap next to the I/O that follows, and a
     * {@code ThreadLocal<ByteBuffer>} works when it is not.
     */
    public byte[] frameSafely(String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);

        ByteBuffer scratch = ByteBuffer.allocate(Integer.BYTES + body.length);
        scratch.putInt(body.length);
        scratch.put(body);
        scratch.flip();

        byte[] out = new byte[scratch.remaining()];
        scratch.get(out);
        return out;
    }

    /**
     * Absolute accessors do not touch {@code position}/{@code limit}/{@code mark}, so they
     * are safe on a shared buffer on their own — the detector records them as context, not
     * as a violation.
     */
    public byte peekAbsolute(int index) {
        return sharedScratch.get(index);
    }
}
