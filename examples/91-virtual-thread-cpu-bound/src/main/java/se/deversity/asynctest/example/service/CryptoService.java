package se.deversity.asynctest.example.service;

/**
 * Performs CPU-intensive encryption, intended to be called from virtual threads.
 *
 * <p>BUG: {@link #encrypt(byte[])} runs a tight CPU loop (simulating heavy
 * computation). When run on a virtual thread the loop never yields, monopolising
 * the carrier thread for the entire duration. Virtual threads should be reserved
 * for I/O-bound work; CPU-bound tasks should use platform threads.
 */
public class CryptoService {

    private static final int WORK_ITERATIONS = 500_000;

    /**
     * Encrypt {@code data} by performing a simulated CPU-intensive operation.
     *
     * <p>BUG: a tight loop on a virtual thread monopolises its carrier. The
     * virtual-thread scheduler cannot preempt pure CPU work.
     *
     * @param data input bytes
     * @return encrypted output bytes
     */
    public byte[] encrypt(byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        byte[] result = new byte[data.length];
        // Simulate CPU-heavy work — no I/O, no yield points.
        // Key is derived from a constant seed (not the data) so the XOR is
        // involutive and decrypt(encrypt(x)) == x.
        long key = 0xC0FFEEDEADBEEFL;
        for (int i = 0; i < WORK_ITERATIONS; i++) {
            key ^= key * 31 + i;
        }
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ (key >> (i % 8)));
        }
        return result;
    }

    /**
     * Decrypt {@code data}. Uses the same XOR operation as encrypt (symmetric).
     */
    public byte[] decrypt(byte[] data) {
        return encrypt(data);
    }
}
