package com.example.agentfixture;

import java.security.GeneralSecurityException;
import java.security.Signature;

import javax.crypto.Cipher;
import javax.crypto.Mac;

/**
 * The call sites of {@link SharedCryptoBean} used the ways that are correct: a primitive built by
 * the call, one held per thread, and a shared one used only under its own monitor.
 */
public class ConfinedCryptoBean {

    private final ThreadLocal<Mac> macPerThread = ThreadLocal.withInitial(CryptoFixtureKeys::mac);
    private final ThreadLocal<Cipher> cipherPerThread = ThreadLocal.withInitial(CryptoFixtureKeys::decrypter);

    /** Shared, and used only under its own monitor. */
    private final Mac guardedMac = CryptoFixtureKeys.mac();

    /** Shared, and used only under its own monitor. */
    private final Signature guardedSigner = CryptoFixtureKeys.signer();

    /** Computes a tag on a {@code Mac} this call built. */
    public void macPerCall() {
        Mac mine = CryptoFixtureKeys.mac();
        mine.update(CryptoFixtureKeys.MESSAGE);
        mine.doFinal();
    }

    /** Computes a tag on this thread's own {@code Mac}. */
    public void macPerThread() {
        macPerThread.get().doFinal(CryptoFixtureKeys.MESSAGE);
    }

    /** Signs and verifies on {@code Signature}s this call built. */
    public void signaturePerCall() throws GeneralSecurityException {
        Signature mine = CryptoFixtureKeys.signer();
        mine.update(CryptoFixtureKeys.MESSAGE);
        byte[] signed = mine.sign();
        Signature check = CryptoFixtureKeys.verifier();
        check.update(CryptoFixtureKeys.MESSAGE);
        check.verify(signed);
    }

    /** Decrypts on this thread's own {@code Cipher}. */
    public void cipherPerThread() throws GeneralSecurityException {
        cipherPerThread.get().doFinal(CryptoFixtureKeys.CIPHERTEXT);
    }

    /** Computes a tag on the shared {@code Mac} under its own monitor. */
    public void macUnderItsMonitor() {
        synchronized (guardedMac) {
            guardedMac.update(CryptoFixtureKeys.MESSAGE);
            guardedMac.doFinal();
        }
    }

    /** Signs with the shared {@code Signature} under its own monitor. */
    public void signatureUnderItsMonitor() throws GeneralSecurityException {
        synchronized (guardedSigner) {
            guardedSigner.update(CryptoFixtureKeys.MESSAGE);
            guardedSigner.sign();
        }
    }
}
