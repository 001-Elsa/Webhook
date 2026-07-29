package com.example.webhook.platform.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.SecureRandom;
import java.util.Arrays;

/** Shared AES-GCM wrap helpers used by local KEK providers and envelope encryption. */
final class LocalAesWrap {
    private static final int NONCE_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private LocalAesWrap() { }

    static byte[] wrap(SecretKey kek, byte[] dek) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(dek);
            byte[] packed = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, packed, 0, nonce.length);
            System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
            return packed;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not wrap data encryption key", ex);
        }
    }

    static byte[] unwrap(SecretKey kek, byte[] wrapped) {
        try {
            if (wrapped.length <= NONCE_BYTES) throw new IllegalArgumentException("Wrapped DEK is truncated");
            byte[] nonce = Arrays.copyOfRange(wrapped, 0, NONCE_BYTES);
            byte[] encrypted = Arrays.copyOfRange(wrapped, NONCE_BYTES, wrapped.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(128, nonce));
            return cipher.doFinal(encrypted);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not unwrap data encryption key", ex);
        }
    }
}
