package se.deversity.asynctest.example.service;

/**
 * An off-heap ring buffer that market-data feed handlers write ticks into.
 *
 * <p>The real version allocates one shared segment and lets every feed thread write into it:
 *
 * <pre>{@code
 * Arena arena = Arena.ofShared();
 * MemorySegment ring = arena.allocate(SLOTS * SLOT_BYTES);
 * // feed thread N:
 * ring.set(JAVA_LONG, slotOffset, price);      // BUG: no partitioning, no lock
 * }</pre>
 *
 * <p>{@code Arena.ofShared()} removes the thread confinement that {@code ofConfined()}
 * imposes, and it is easy to read that as "now it is thread-safe". It is not. Shared means
 * every thread is <em>allowed</em> to access the segment; it says nothing about what happens
 * when two of them touch the same bytes. Off-heap memory gets no more help from the Java
 * memory model than a plain field does — overlapping writes tear, and a reader can observe
 * half of one tick and half of another.
 *
 * <p>There are exactly two correct shapes, and this class shows both:
 *
 * <ul>
 *   <li><b>Partition it.</b> Give each writer its own slice with {@code asSlice(offset, len)}.
 *       Disjoint ranges cannot race, and no lock is needed. This is the fast answer.</li>
 *   <li><b>Guard it.</b> If the ranges genuinely overlap, put every access behind the same
 *       monitor. Two threads holding the same lock are mutually excluded.</li>
 * </ul>
 *
 * <p>What is never correct is the third shape: overlapping ranges, no guard, and a comment
 * saying the arena is shared.
 *
 * <p>The segment here is a plain object because {@code java.lang.foreign} is still a preview
 * API on the Java 21 baseline these examples compile against. The detector keys on identity
 * plus the {@code [offset, offset+length)} range each thread touched, so the interval
 * arithmetic it performs is the same either way.
 */
public final class MarketDataRingBuffer {

    /** Stands in for {@code java.lang.foreign.MemorySegment}. */
    public static final class Segment {
        private final byte[] bytes;

        public Segment(int size) {
            this.bytes = new byte[size];
        }

        public void putLong(int offset, long value) {
            for (int i = 0; i < Long.BYTES; i++) {
                bytes[offset + i] = (byte) (value >>> (8 * (Long.BYTES - 1 - i)));
            }
        }

        public long getLong(int offset) {
            long v = 0;
            for (int i = 0; i < Long.BYTES; i++) {
                v = (v << 8) | (bytes[offset + i] & 0xFFL);
            }
            return v;
        }
    }

    public static final int SLOT_BYTES = Long.BYTES;
    public static final int SLOTS = 8;

    private final Segment ring = new Segment(SLOTS * SLOT_BYTES);

    /** The lock the guarded variant agrees on. */
    public static final String GUARD = "ringLock";

    private final Object ringLock = new Object();

    public Segment ring() {
        return ring;
    }

    /** Byte offset of a writer's own slot — the partitioned, lock-free answer. */
    public static int slotOffset(int writerIndex) {
        return (writerIndex % SLOTS) * SLOT_BYTES;
    }

    /** BUG: every writer targets slot 0, so the ranges overlap and nothing guards them. */
    public void publishUnpartitioned(long price) {
        ring.putLong(0, price);
    }

    /** The fix: each writer owns one slice, so no two writers share a byte. */
    public void publishToOwnSlot(int writerIndex, long price) {
        ring.putLong(slotOffset(writerIndex), price);
    }

    /** The other fix: overlapping ranges are fine when every access takes the same monitor. */
    public void publishUnderGuard(long price) {
        synchronized (ringLock) {
            ring.putLong(0, price);
        }
    }

    public long readSlot(int writerIndex) {
        return ring.getLong(slotOffset(writerIndex));
    }
}
