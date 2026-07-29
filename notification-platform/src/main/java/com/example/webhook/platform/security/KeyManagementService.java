package com.example.webhook.platform.security;

import javax.crypto.SecretKey;

/**
 * Boundary for local KEKs, Vault Transit, or cloud KMS envelope-key providers.
 * Local providers expose AES keys; remote providers wrap/unwrap DEKs via transit APIs.
 */
public interface KeyManagementService {
    int activeVersion();

    /**
     * Returns a local AES KEK. Vault/AWS adapters may throw if direct key export is unsupported.
     */
    SecretKey keyForVersion(int version);

    /** Wrap a data-encryption key for storage alongside ciphertext. */
    default byte[] wrapDataKey(byte[] dek) {
        return LocalAesWrap.wrap(keyForVersion(activeVersion()), dek);
    }

    /** Unwrap a previously wrapped DEK using the given KEK version. */
    default byte[] unwrapDataKey(int kekVersion, byte[] wrappedDek) {
        return LocalAesWrap.unwrap(keyForVersion(kekVersion), wrappedDek);
    }
}
