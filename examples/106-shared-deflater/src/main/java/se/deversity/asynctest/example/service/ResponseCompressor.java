package se.deversity.asynctest.example.service;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;

/**
 * Compresses HTTP response payloads using a {@link Deflater}.
 *
 * <p><strong>Bug:</strong> A single {@link Deflater} instance is held in a field and
 * shared across all threads. {@link Deflater} wraps a stateful native zlib stream and
 * is not thread-safe: concurrent {@code reset()}/{@code setInput()}/{@code deflate()}
 * calls interleave on the same native state, corrupting the compressed output or
 * producing garbage bytes.
 *
 * <p><strong>Fix:</strong> Use one {@link Deflater} per thread (e.g. a
 * {@link ThreadLocal}) and always call {@code end()} in a {@code finally} block to
 * release the native resource, or create a fresh {@code Deflater} per call and
 * {@code end()} it when done.
 */
public class ResponseCompressor {

    // BUG: a Deflater wraps a stateful native zlib stream and is not thread-safe —
    // do not share a single instance across threads
    private final Deflater deflater = new Deflater();

    /**
     * Compresses the given data using the shared deflater. Not thread-safe.
     */
    public byte[] compress(byte[] data) {
        deflater.reset();
        deflater.setInput(data);
        deflater.finish();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[256];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Returns the shared deflater for test instrumentation. */
    public Deflater getDeflater() {
        return deflater;
    }
}
