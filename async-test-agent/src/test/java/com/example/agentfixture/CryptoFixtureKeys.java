package com.example.agentfixture;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keys and ready-initialised primitives for the crypto fixtures, generated once per JVM.
 *
 * <p>The cipher decrypts: an AES/GCM cipher may be reused after {@code doFinal} for decryption
 * with the nonce it was initialised with, where encryption would refuse the second call.
 */
final class CryptoFixtureKeys {

    static final byte[] MESSAGE = {1, 2, 3, 4, 5, 6, 7, 8};

    private static final SecretKey MAC_KEY;
    private static final SecretKey AES_KEY;
    private static final KeyPair SIGNING;
    private static final byte[] NONCE = new byte[12];
    static final byte[] CIPHERTEXT;
    static final byte[] SIGNATURE;

    static {
        try {
            MAC_KEY = KeyGenerator.getInstance("HmacSHA256").generateKey();
            AES_KEY = KeyGenerator.getInstance("AES").generateKey();
            KeyPairGenerator pairs = KeyPairGenerator.getInstance("EC");
            pairs.initialize(new ECGenParameterSpec("secp256r1"));
            SIGNING = pairs.generateKeyPair();
            new SecureRandom().nextBytes(NONCE);
            Cipher encrypt = Cipher.getInstance("AES/GCM/NoPadding");
            encrypt.init(Cipher.ENCRYPT_MODE, AES_KEY, new GCMParameterSpec(128, NONCE));
            CIPHERTEXT = encrypt.doFinal(MESSAGE);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(SIGNING.getPrivate());
            signer.update(MESSAGE);
            SIGNATURE = signer.sign();
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private CryptoFixtureKeys() {
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
            signature.initSign(SIGNING.getPrivate());
            return signature;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static Signature verifier() {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(SIGNING.getPublic());
            return signature;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static Cipher decrypter() {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, AES_KEY, new GCMParameterSpec(128, NONCE));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
