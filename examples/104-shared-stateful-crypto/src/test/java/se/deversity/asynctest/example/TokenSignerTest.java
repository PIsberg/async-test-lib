package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.TokenSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for TokenSigner.
 *
 * ========================================================================
 * DETECTOR: SharedStatefulCryptoDetector
 * ========================================================================
 *
 * THE BUG:
 * TokenSigner creates one HmacSHA256 Mac instance and stores it in a field that is
 * shared across all threads. A Mac is stateful: a signing operation is init -> update
 * -> doFinal, and the intermediate bytes accumulate inside the instance. When two
 * threads sign concurrently on the same Mac, their input bytes interleave into one
 * shared buffer, producing a MAC that verifies for neither input (or throwing
 * IllegalStateException).
 *
 * WHY @Test PASSES:
 * Single-threaded tests always complete their full init/update/doFinal sequence before
 * any other thread can touch the Mac. The shared instance behaves correctly in isolation.
 *
 * WHY @AsyncTest DETECTS:
 * With 8 threads sharing the same TokenSigner (and thus the same Mac) instance,
 * SharedStatefulCryptoDetector tracks which threads access the Mac and reports the
 * multi-thread access pattern on a stateful crypto object.
 *
 * FIX:
 * Never share a stateful Mac across threads. Use a ThreadLocal<Mac> so each thread owns
 * its own instance, or create and init a fresh Mac per sign() call.
 */
class TokenSignerTest {

    private TokenSigner signer;

    @BeforeEach
    void setUp() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        signer = new TokenSigner(key);
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testSign_singleThread_returns32Bytes() {
        byte[] mac = signer.sign("hello");
        assertEquals(32, mac.length);
    }

    @Test
    void testSign_singleThread_nonNull() {
        byte[] mac = signer.sign("payload");
        assertNotNull(mac);
    }

    @Test
    void testSign_sameMessage_isDeterministic() {
        byte[] first = signer.sign("repeat");
        byte[] second = signer.sign("repeat");
        assertArrayEquals(first, second);
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the shared stateful Mac bug
    // -------------------------------------------------------------------------

    /**
     * Eight threads concurrently sign messages using the same TokenSigner, and thus
     * the same shared Mac instance. SharedStatefulCryptoDetector records all accesses
     * and reports that the same stateful Mac is used from multiple threads unsafely.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: use a ThreadLocal<Mac> or a fresh Mac per call
     */
    @Disabled("Remove @Disabled to see the bug detected by SharedStatefulCryptoDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectSharedStatefulCrypto = true, failOn = FailOn.LOW)
    void test_concurrent_detectsSharedMac() {
        Thread thread = Thread.currentThread();
        AsyncTestContext.sharedStatefulCryptoDetector().recordAccess(signer.getMac(), "hmac-signer", thread);
        signer.sign("payload-" + thread.getName());
    }
}
