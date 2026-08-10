package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Parses network packets into an off-heap scratch buffer.
 *
 * <p>The real version of this class allocates from {@code java.lang.foreign}:
 *
 * <pre>{@code
 * try (Arena arena = Arena.ofConfined()) {           // confined to THIS thread
 *     MemorySegment buffer = arena.allocate(1024);
 *     writeHeader(buffer);
 *     return pool.submit(() -> checksum(buffer));    // BUG: another thread touches it
 * }                                                   // BUG: and the arena closes here
 * }</pre>
 *
 * <p>{@code Arena.ofConfined()} makes exactly one promise: every access to a segment it
 * allocated happens on the thread that opened it. Any other thread gets a
 * {@code WrongThreadException} — not a corrupted read, not a torn value, a hard failure at
 * the first access. That is the good case.
 *
 * <p>The bad case is the closing brace. A confined arena frees its memory when the
 * try-with-resources ends, and the owner does not wait for the worker. If the submitted task
 * has not run yet, the segment it captured now points at memory the allocator has reclaimed,
 * and the access is an {@code IllegalStateException} at best or a JVM crash at worst.
 *
 * <p>This example models the same lifecycle with plain objects, because {@code
 * java.lang.foreign} is still a preview API on the Java 21 baseline these examples compile
 * against. The arena and segment below stand in for the real ones exactly as the library's own
 * unit tests do: {@code ConfinedArenaThreadEscapeDetector} works on object identity and the
 * thread that touches it, so the pattern it sees is identical.
 *
 * <p>The fix is to pick the arena that matches the lifetime. {@code Arena.ofShared()} allows
 * access from any thread and stays alive until it is explicitly closed; if the buffer must
 * outlive the enclosing block, it never belonged in a confined arena.
 */
public final class PacketParser {

    /** Stands in for {@code java.lang.foreign.Arena}. */
    public static final class Arena {
        private final String name;

        public Arena(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return "Arena[" + name + "]";
        }
    }

    /** Stands in for {@code java.lang.foreign.MemorySegment}. */
    public static final class Segment {
        private final byte[] bytes;

        public Segment(int size) {
            this.bytes = new byte[size];
        }

        public int size() {
            return bytes.length;
        }

        public void put(int index, byte value) {
            bytes[index] = value;
        }

        public byte get(int index) {
            return bytes[index];
        }
    }

    public static final int BUFFER_BYTES = 1024;

    /**
     * BUG: allocates from a confined arena and then hands the segment to a pool thread.
     *
     * <p>Returns the futures so a caller can join them, which the real bug does not bother to
     * do — that is precisely how the use-after-close arises.
     */
    public List<Future<Integer>> parseAndOffloadChecksum(ExecutorService pool, int packets) {
        Arena arena = new Arena("packet-scratch");
        Segment buffer = new Segment(BUFFER_BYTES);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < packets; i++) {
            buffer.put(i % BUFFER_BYTES, (byte) i);
            futures.add(pool.submit(() -> checksum(buffer)));
        }
        return futures;
    }

    /** The fix: the owning thread does the work, so confinement is never violated. */
    public int parseAndChecksumOnOwningThread(int packets) {
        Segment buffer = new Segment(BUFFER_BYTES);
        for (int i = 0; i < packets; i++) {
            buffer.put(i % BUFFER_BYTES, (byte) i);
        }
        return checksum(buffer);
    }

    private int checksum(Segment segment) {
        int sum = 0;
        for (int i = 0; i < segment.size(); i++) {
            sum += segment.get(i);
        }
        return sum;
    }
}
