package com.example.agentfixture;

import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.Mac;

/**
 * One stateful JCA primitive per field, initialised once and used by every thread with no lock.
 *
 * <p>A {@code Mac}, a {@code Signature} and a {@code Cipher} each fold the bytes they are given
 * into one running state, so two threads sharing one compute a tag, a signature or a plaintext
 * over a mix of both inputs, or throw from inside the provider. The agent wove none of their
 * calls, so each of these was invisible however many threads shared the instance (#1321).
 */
public class SharedCryptoBean {

    private final Mac updatedMac = CryptoFixtureKeys.mac();
    private final Mac finishedMac = CryptoFixtureKeys.mac();
    private final Signature updatedSigner = CryptoFixtureKeys.signer();
    private final Signature signer = CryptoFixtureKeys.signer();
    private final Signature verifier = CryptoFixtureKeys.verifier();
    private final Cipher updatedCipher = CryptoFixtureKeys.decrypter();
    private final Cipher finishedCipher = CryptoFixtureKeys.decrypter();

    /** How many calls the race broke. */
    private final AtomicInteger racesAbsorbed = new AtomicInteger();

    /** Adds bytes to the shared {@code Mac}. */
    public void macUpdate() {
        absorbing(() -> updatedMac.update(CryptoFixtureKeys.MESSAGE));
    }

    /** Computes a tag in one call on the shared {@code Mac}. */
    public void macDoFinal() {
        absorbing(() -> finishedMac.doFinal(CryptoFixtureKeys.MESSAGE));
    }

    /** Adds bytes to the shared signing {@code Signature}. */
    public void signatureUpdate() {
        absorbing(() -> updatedSigner.update(CryptoFixtureKeys.MESSAGE));
    }

    /** Signs with the shared {@code Signature}. */
    public void signatureSign() {
        absorbing(signer::sign);
    }

    /** Verifies with the shared {@code Signature}. */
    public void signatureVerify() {
        absorbing(() -> verifier.verify(CryptoFixtureKeys.SIGNATURE));
    }

    /** Feeds bytes to the shared {@code Cipher}. */
    public void cipherUpdate() {
        absorbing(() -> updatedCipher.update(CryptoFixtureKeys.MESSAGE));
    }

    /** Decrypts in one call on the shared {@code Cipher}. */
    public void cipherDoFinal() {
        absorbing(() -> finishedCipher.doFinal(CryptoFixtureKeys.CIPHERTEXT));
    }

    /** {@return how many calls the race broke} */
    public int racesAbsorbed() {
        return racesAbsorbed.get();
    }

    private void absorbing(Call call) {
        try {
            call.run();
        } catch (GeneralSecurityException | RuntimeException raced) {
            racesAbsorbed.incrementAndGet();
        }
    }

    /** One call on a primitive. */
    private interface Call {
        void run() throws GeneralSecurityException;
    }
}
