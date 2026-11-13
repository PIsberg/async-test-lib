package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.concurrent.CyclicBarrier;
import java.util.function.Supplier;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.GCMParameterSpec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The woven {@code Mac}, {@code Signature} and {@code Cipher} hooks (#1321): each overload hands
 * its arguments to the primitive unchanged and returns what it returned, a primitive two threads
 * share is reported as what it is, and one per thread is not.
 *
 * <p>A hook that swapped an offset for a length would still record, so the delegation is checked
 * against a tag, a signature and a plaintext computed without the hooks. The cipher decrypts
 * throughout: AES/GCM allows a nonce to be reused for decryption and refuses it for encryption.
 */
class AgentCryptoHooksTest {

    private static final byte[] MESSAGE = "sixteen byte msg".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AAD = {9, 8, 7};

    private static final SecretKey MAC_KEY;
    private static final SecretKey AES_KEY;
    private static final KeyPair PAIR;
    private static final byte[] NONCE = new byte[12];
    private static final byte[] AAD_NONCE = new byte[12];
    private static final byte[] TAG;
    private static final byte[] CIPHERTEXT;
    private static final byte[] CIPHERTEXT_WITH_AAD;
    private static final byte[] SIGNATURE;

    static {
        try {
            MAC_KEY = KeyGenerator.getInstance("HmacSHA256").generateKey();
            AES_KEY = KeyGenerator.getInstance("AES").generateKey();
            KeyPairGenerator pairs = KeyPairGenerator.getInstance("EC");
            pairs.initialize(new ECGenParameterSpec("secp256r1"));
            PAIR = pairs.generateKeyPair();
            SecureRandom random = new SecureRandom();
            random.nextBytes(NONCE);
            random.nextBytes(AAD_NONCE);
            TAG = mac().doFinal(MESSAGE);
            Cipher plain = Cipher.getInstance("AES/GCM/NoPadding");
            plain.init(Cipher.ENCRYPT_MODE, AES_KEY, new GCMParameterSpec(128, NONCE));
            CIPHERTEXT = plain.doFinal(MESSAGE);
            Cipher bound = Cipher.getInstance("AES/GCM/NoPadding");
            bound.init(Cipher.ENCRYPT_MODE, AES_KEY, new GCMParameterSpec(128, AAD_NONCE));
            bound.updateAAD(AAD);
            CIPHERTEXT_WITH_AAD = bound.doFinal(MESSAGE);
            Signature signer = signer();
            signer.update(MESSAGE);
            SIGNATURE = signer.sign();
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static Mac mac() {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(MAC_KEY);
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static Signature signer() {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(PAIR.getPrivate());
            return signature;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Signature verifier() {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(PAIR.getPublic());
            signature.update(MESSAGE);
            return signature;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static Cipher decrypter(boolean withAad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, AES_KEY, new GCMParameterSpec(128, withAad ? AAD_NONCE : NONCE));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code bytes} with one byte before it and one after, so an offset and a length both matter. */
    private static byte[] framed(byte[] bytes) {
        byte[] framed = new byte[bytes.length + 2];
        System.arraycopy(bytes, 0, framed, 1, bytes.length);
        return framed;
    }

    private static boolean verifies(byte[] signature) throws GeneralSecurityException {
        return verifier().verify(signature);
    }

    @Test
    @DisplayName("every Mac hook computes the tag the Mac computes")
    void macHooksDelegate() throws Exception {
        Mac mac = mac();
        for (byte b : MESSAGE) {
            AgentSharedInstanceHooks.update(mac, b);
        }
        assertArrayEquals(TAG, AgentSharedInstanceHooks.doFinal(mac), "update(byte), doFinal()");

        mac = mac();
        AgentSharedInstanceHooks.update(mac, MESSAGE);
        assertArrayEquals(TAG, mac.doFinal(), "update(byte[])");

        mac = mac();
        AgentSharedInstanceHooks.update(mac, framed(MESSAGE), 1, MESSAGE.length);
        byte[] out = new byte[TAG.length + 1];
        AgentSharedInstanceHooks.doFinal(mac, out, 1);
        assertArrayEquals(TAG, Arrays.copyOfRange(out, 1, out.length), "update(byte[], int, int), doFinal(byte[], int)");

        mac = mac();
        AgentSharedInstanceHooks.update(mac, ByteBuffer.wrap(MESSAGE));
        assertArrayEquals(TAG, mac.doFinal(), "update(ByteBuffer)");

        assertArrayEquals(TAG, AgentSharedInstanceHooks.doFinal(mac(), MESSAGE), "doFinal(byte[])");
        assertThrows(ShortBufferException.class, () -> AgentSharedInstanceHooks.doFinal(mac(), new byte[1], 0),
                "the Mac's own exception reaches the caller");
    }

    @Test
    @DisplayName("every Signature hook signs or verifies as the Signature does")
    void signatureHooksDelegate() throws Exception {
        Signature signer = signer();
        for (byte b : MESSAGE) {
            AgentSharedInstanceHooks.update(signer, b);
        }
        assertTrue(verifies(AgentSharedInstanceHooks.sign(signer)), "update(byte), sign()");

        signer = signer();
        AgentSharedInstanceHooks.update(signer, MESSAGE);
        assertTrue(verifies(signer.sign()), "update(byte[])");

        signer = signer();
        AgentSharedInstanceHooks.update(signer, framed(MESSAGE), 1, MESSAGE.length);
        byte[] out = new byte[129];
        int length = AgentSharedInstanceHooks.sign(signer, out, 1, 128);
        assertTrue(verifies(Arrays.copyOfRange(out, 1, 1 + length)), "update(byte[], int, int), sign(byte[], int, int)");

        signer = signer();
        AgentSharedInstanceHooks.update(signer, ByteBuffer.wrap(MESSAGE));
        assertTrue(verifies(signer.sign()), "update(ByteBuffer)");

        assertTrue(AgentSharedInstanceHooks.verify(verifier(), SIGNATURE), "verify(byte[])");
        assertTrue(AgentSharedInstanceHooks.verify(verifier(), framed(SIGNATURE), 1, SIGNATURE.length),
                "verify(byte[], int, int)");
        Signature uninitialised = Signature.getInstance("SHA256withECDSA");
        assertThrows(SignatureException.class, () -> AgentSharedInstanceHooks.sign(uninitialised),
                "the Signature's own exception reaches the caller");
    }

    @Test
    @DisplayName("every Cipher hook decrypts what the Cipher decrypts")
    void cipherHooksDelegate() throws Exception {
        byte[] in = framed(CIPHERTEXT);
        int n = CIPHERTEXT.length;

        Cipher cipher = decrypter(false);
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        append(plain, AgentSharedInstanceHooks.update(cipher, CIPHERTEXT));
        append(plain, AgentSharedInstanceHooks.doFinal(cipher));
        assertArrayEquals(MESSAGE, plain.toByteArray(), "update(byte[]), doFinal()");

        cipher = decrypter(false);
        plain.reset();
        append(plain, AgentSharedInstanceHooks.update(cipher, in, 1, n));
        byte[] out = new byte[65];
        int written = AgentSharedInstanceHooks.doFinal(cipher, out, 1);
        plain.write(out, 1, written);
        assertArrayEquals(MESSAGE, plain.toByteArray(), "update(byte[], int, int), doFinal(byte[], int)");

        cipher = decrypter(false);
        out = new byte[64];
        written = AgentSharedInstanceHooks.update(cipher, in, 1, n, out);
        assertArrayEquals(MESSAGE, joined(out, 0, written, cipher.doFinal()), "update(byte[], int, int, byte[])");

        cipher = decrypter(false);
        out = new byte[65];
        written = AgentSharedInstanceHooks.update(cipher, in, 1, n, out, 1);
        assertArrayEquals(MESSAGE, joined(out, 1, written, cipher.doFinal()),
                "update(byte[], int, int, byte[], int)");

        cipher = decrypter(false);
        ByteBuffer buffer = ByteBuffer.allocate(64);
        AgentSharedInstanceHooks.update(cipher, ByteBuffer.wrap(CIPHERTEXT), buffer);
        assertArrayEquals(MESSAGE, joined(buffer.array(), 0, buffer.position(), cipher.doFinal()),
                "update(ByteBuffer, ByteBuffer)");

        assertArrayEquals(MESSAGE, AgentSharedInstanceHooks.doFinal(decrypter(false), CIPHERTEXT), "doFinal(byte[])");
        assertArrayEquals(MESSAGE, AgentSharedInstanceHooks.doFinal(decrypter(false), in, 1, n),
                "doFinal(byte[], int, int)");

        out = new byte[64];
        written = AgentSharedInstanceHooks.doFinal(decrypter(false), in, 1, n, out);
        assertArrayEquals(MESSAGE, Arrays.copyOf(out, written), "doFinal(byte[], int, int, byte[])");

        out = new byte[65];
        written = AgentSharedInstanceHooks.doFinal(decrypter(false), in, 1, n, out, 1);
        assertArrayEquals(MESSAGE, Arrays.copyOfRange(out, 1, 1 + written), "doFinal(byte[], int, int, byte[], int)");

        buffer = ByteBuffer.allocate(64);
        written = AgentSharedInstanceHooks.doFinal(decrypter(false), ByteBuffer.wrap(CIPHERTEXT), buffer);
        assertEquals(MESSAGE.length, written, "doFinal(ByteBuffer, ByteBuffer) returns the count");
        assertArrayEquals(MESSAGE, Arrays.copyOf(buffer.array(), written), "doFinal(ByteBuffer, ByteBuffer)");

        byte[] corrupted = CIPHERTEXT.clone();
        corrupted[0] ^= 1;
        assertThrows(AEADBadTagException.class, () -> AgentSharedInstanceHooks.doFinal(decrypter(false), corrupted),
                "the Cipher's own exception reaches the caller");
    }

    @Test
    @DisplayName("every updateAAD hook binds the data the Cipher binds")
    void cipherAadHooksDelegate() throws Exception {
        Cipher cipher = decrypter(true);
        AgentSharedInstanceHooks.updateAAD(cipher, AAD);
        assertArrayEquals(MESSAGE, cipher.doFinal(CIPHERTEXT_WITH_AAD), "updateAAD(byte[])");

        cipher = decrypter(true);
        AgentSharedInstanceHooks.updateAAD(cipher, framed(AAD), 1, AAD.length);
        assertArrayEquals(MESSAGE, cipher.doFinal(CIPHERTEXT_WITH_AAD), "updateAAD(byte[], int, int)");

        cipher = decrypter(true);
        AgentSharedInstanceHooks.updateAAD(cipher, ByteBuffer.wrap(AAD));
        assertArrayEquals(MESSAGE, cipher.doFinal(CIPHERTEXT_WITH_AAD), "updateAAD(ByteBuffer)");
    }

    @Test
    @DisplayName("a shared Mac, Signature or Cipher is reported as what it is; one per thread is not")
    void sharedIsReportedAndConfinedIsNot() {
        Mac sharedMac = mac();
        String report = reportOf(() -> sharedMac, mac -> AgentSharedInstanceHooks.update(mac, MESSAGE));
        assertTrue(report.contains("Mac is stateful") && report.contains("algorithm=HmacSHA256"), report);

        Signature sharedSigner = signer();
        report = reportOf(() -> sharedSigner, signer -> AgentSharedInstanceHooks.update(signer, MESSAGE));
        assertTrue(report.contains("Signature is stateful") && report.contains("algorithm=SHA256withECDSA"), report);

        Cipher sharedCipher = decrypter(false);
        report = reportOf(() -> sharedCipher, cipher -> AgentSharedInstanceHooks.update(cipher, CIPHERTEXT));
        assertTrue(report.contains("Cipher is stateful") && report.contains("algorithm=AES/GCM/NoPadding"), report);

        assertEquals("", reportOf(AgentCryptoHooksTest::mac, mac -> AgentSharedInstanceHooks.doFinal(mac, MESSAGE)),
                "one Mac per thread is correct code");
        assertEquals("", reportOf(AgentCryptoHooksTest::signer, signer -> AgentSharedInstanceHooks.sign(signer)),
                "one Signature per thread is correct code");
        assertEquals("", reportOf(() -> decrypter(false),
                        cipher -> AgentSharedInstanceHooks.doFinal(cipher, CIPHERTEXT)),
                "one Cipher per thread is correct code");
    }

    @Test
    @DisplayName("outside a run the hooks only delegate")
    void outsideARunTheHooksOnlyDelegate() throws Exception {
        assertArrayEquals(TAG, AgentSharedInstanceHooks.doFinal(mac(), MESSAGE));
    }

    /** The findings after two threads each ran {@code use} on what {@code source} gave them. */
    private static <T> String reportOf(Supplier<T> source, Use<T> use) {
        AsyncTestContext ctx = new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());
        CyclicBarrier barrier = new CyclicBarrier(2);
        Runnable body = () -> {
            AsyncTestContext.install(ctx);
            try {
                T mine = source.get();
                barrier.await();
                use.on(mine);
            } catch (Exception raced) { // NOPMD - a shared primitive throwing is the bug; it was recorded first
                return;
            } finally {
                AsyncTestContext.uninstall();
            }
        };
        Thread a = new Thread(body, "crypto-hooks-1");
        Thread b = new Thread(body, "crypto-hooks-2");
        a.start();
        b.start();
        try {
            a.join();
            b.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        AsyncTestContext.install(ctx);
        try {
            return String.join("\n", AsyncTestContext.sharedStatefulCryptoDetector().analyze().violations);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    private static void append(ByteArrayOutputStream to, byte[] bytes) {
        if (bytes != null) {
            to.writeBytes(bytes);
        }
    }

    private static byte[] joined(byte[] first, int offset, int length, byte[] rest) {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(first, offset, length);
        all.writeBytes(rest);
        return all.toByteArray();
    }

    /** One use of a primitive, which may throw what the primitive throws. */
    private interface Use<T> {
        void on(T primitive) throws Exception;
    }
}
