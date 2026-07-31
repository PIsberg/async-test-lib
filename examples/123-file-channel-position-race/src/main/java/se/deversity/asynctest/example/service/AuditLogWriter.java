package se.deversity.asynctest.example.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Appends audit records to a file that several request threads write to.
 *
 * <p>{@link FileChannel} is thread-safe in the sense the javadoc means: concurrent calls
 * will not corrupt the channel object itself. That is a much weaker guarantee than it
 * sounds, and the same javadoc says the rest out loud:
 *
 * <blockquote>The view of a file provided by an instance of this class [...] Where the
 * {@code position} is affected, [operations] are not safe for use by multiple concurrent
 * threads.</blockquote>
 *
 * <p>Concretely: {@code write(ByteBuffer)} and {@code read(ByteBuffer)} use the channel's
 * <em>implicit</em> position and advance it. One cursor, every thread. Two appends racing on
 * it land at unpredictable offsets — one record overwrites another, or a record is split
 * across two others' bytes.
 *
 * <p>The positional overloads {@code write(ByteBuffer, long)} and {@code read(ByteBuffer,
 * long)} take an explicit offset and do not touch the shared cursor. Those are the safe
 * ones, and they are the fix.
 */
public final class AuditLogWriter implements AutoCloseable {

    private final FileChannel channel;

    /** Where the next positional write goes. Explicit, so no shared cursor is needed. */
    private final AtomicLong nextOffset = new AtomicLong();

    public AuditLogWriter(Path file) throws IOException {
        this.channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    /**
     * BUG: implicit-position write. Every caller advances the one shared cursor, so two
     * concurrent appends interleave at offsets neither of them chose.
     */
    public void append(String record) throws IOException {
        byte[] bytes = (record + "\n").getBytes(StandardCharsets.UTF_8);
        channel.write(ByteBuffer.wrap(bytes));
    }

    /**
     * The fix: positional write. The offset is reserved atomically and passed explicitly, so
     * the channel's cursor is never consulted and never moved.
     */
    public void appendSafely(String record) throws IOException {
        byte[] bytes = (record + "\n").getBytes(StandardCharsets.UTF_8);
        long offset = nextOffset.getAndAdd(bytes.length);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            offset += channel.write(buffer, offset);
        }
    }

    /** BUG: implicit-position read — same shared cursor, same race. */
    public String readFrom() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(256);
        int read = channel.read(buffer);
        return read <= 0 ? "" : new String(buffer.array(), 0, read, StandardCharsets.UTF_8);
    }

    /** The fix, reading: an explicit offset. */
    public String readAt(long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        int read = channel.read(buffer, offset);
        return read <= 0 ? "" : new String(buffer.array(), 0, read, StandardCharsets.UTF_8);
    }

    public long size() throws IOException {
        return channel.size();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
