package se.deversity.asynctest.example.service;

/**
 * Performs CPU-intensive encryption, intended to be called from virtual threads.
 *
 * <p>BUG: {@link #encrypt(byte[])} runs a tight CPU loop with no I/O and no yield points.
 * On a virtual thread that loop monopolises the carrier for its whole duration: virtual
 * threads unmount when they park, and pure computation never parks. Ten thousand virtual
 * threads doing CPU work are ten thousand tasks queued behind however many carriers there
 * are, which is the number of cores, which is what platform threads already gave you.
 *
 * <p>FIX: keep virtual threads for I/O-bound work and run CPU-bound work on a bounded
 * platform-thread pool sized to the core count.
 */
public class CryptoService {

    /**
     * How long {@link #encrypt(byte[])} occupies its carrier.
     *
     * <p>Bounded by the clock rather than by an iteration count on purpose. An iteration
     * count is not a duration: the loop this replaced ran 500,000 rounds of integer
     * arithmetic, which the JIT finishes in well under a millisecond, so the task never
     * approached VirtualThreadCpuBoundTaskDetector's 50ms threshold and the demonstration
     * fired only when the machine happened to be busy enough. See issue #346.
     */
    public static final long CPU_WORK_MILLIS = 120L;

    private static final int KEY_ROUNDS = 500_000;

    /** Written by the burn loop so the JIT cannot delete it. */
    @SuppressWarnings("unused")
    private volatile long blackhole;

    /**
     * Encrypt {@code data}, doing {@link #CPU_WORK_MILLIS} of computation on the way.
     *
     * @param data input bytes
     * @return encrypted output bytes
     */
    public byte[] encrypt(byte[] data) {
        return encrypt(data, CPU_WORK_MILLIS);
    }

    /**
     * Encrypt {@code data}, doing {@code workMillis} of computation on the way.
     *
     * <p>BUG: that computation has no yield point in it. On a virtual thread the carrier is
     * unavailable to anybody else until this returns.
     *
     * @param data       input bytes
     * @param workMillis how long to occupy the carrier
     * @return encrypted output bytes
     */
    public byte[] encrypt(byte[] data, long workMillis) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        burnCpu(workMillis);

        // The key is derived from a constant seed rather than the data, so the XOR is
        // involutive and decrypt(encrypt(x)) == x whatever workMillis was.
        long key = 0xC0FFEEDEADBEEFL;
        for (int i = 0; i < KEY_ROUNDS; i++) {
            key ^= key * 31 + i;
        }
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ (key >> (i % 8)));
        }
        return result;
    }

    /**
     * Decrypt {@code data}. The same XOR as encrypt, so it is symmetric.
     *
     * @param data the ciphertext
     * @return the plaintext
     */
    public byte[] decrypt(byte[] data) {
        return encrypt(data, 0L);
    }

    /** Occupies the calling thread for {@code millis}, with no yield point anywhere in it. */
    private void burnCpu(long millis) {
        if (millis <= 0) {
            return;
        }
        long deadline = System.nanoTime() + millis * 1_000_000L;
        long scratch = 1;
        long round = 0;
        while (System.nanoTime() < deadline) {
            scratch ^= scratch * 31 + round++;
        }
        blackhole = scratch;
    }
}
