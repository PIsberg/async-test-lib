package se.deversity.asynctest.example.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Appends fixed-size audit records to a file and reads them back by index, from several
 * request threads sharing one channel.
 *
 * <p>{@link FileChannel} is documented as safe for use by multiple concurrent threads, and it
 * says how: "Only one operation that involves the channel's position or can change its file's
 * size may be in progress at any given time". So a single {@code write(ByteBuffer)} or
 * {@code read(ByteBuffer)} completes whole, at the offset the shared cursor held when it
 * started. {@link #append} relies on nothing more than that, and every record lands whole.
 *
 * <p>What the channel cannot make atomic is two calls. {@link #readRecord} sets the position and
 * then reads, and another thread's call can land between the two and move the cursor. The read
 * then returns some other record.
 *
 * <p>The positional overload {@code read(ByteBuffer, long)} takes the offset as an argument and
 * neither reads nor moves the cursor: {@link #readRecordAt} is the fix.
 */
public final class AuditLogWriter implements AutoCloseable {

    /** Every record is padded to this many bytes, so record {@code i} starts at {@code i * RECORD_SIZE}. */
    public static final int RECORD_SIZE = 16;

    private final FileChannel channel;

    public AuditLogWriter(Path file) throws IOException {
        this(FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE));
    }

    /** Over a channel the caller opened; the writer closes it. */
    public AuditLogWriter(FileChannel channel) {
        this.channel = channel;
    }

    /**
     * Appends one record with the implicit-position {@code write(ByteBuffer)}. Self-contained:
     * the channel runs one such call at a time, so concurrent appends each land whole, in an
     * order nobody chose, and none is lost.
     */
    public void append(String record) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(pad(record));
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /**
     * BUG: seek, then read relying on the seek. Another thread's call between the two moves the
     * cursor, and this returns a record other than {@code index}.
     */
    public String readRecord(int index) throws IOException {
        channel.position((long) index * RECORD_SIZE);
        ByteBuffer buffer = ByteBuffer.allocate(RECORD_SIZE);
        channel.read(buffer);
        return decode(buffer);
    }

    /** The fix: the offset is an argument, and the shared cursor is neither read nor moved. */
    public String readRecordAt(int index) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(RECORD_SIZE);
        long offset = (long) index * RECORD_SIZE;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset + buffer.position());
            if (read < 0) {
                break;
            }
        }
        return decode(buffer);
    }

    public long size() throws IOException {
        return channel.size();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private static byte[] pad(String record) {
        byte[] bytes = record.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > RECORD_SIZE) {
            throw new IllegalArgumentException("record longer than " + RECORD_SIZE + " bytes: " + record);
        }
        byte[] padded = new byte[RECORD_SIZE];
        Arrays.fill(padded, (byte) ' ');
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        return padded;
    }

    private static String decode(ByteBuffer buffer) {
        return new String(buffer.array(), 0, buffer.position(), StandardCharsets.US_ASCII).trim();
    }
}
