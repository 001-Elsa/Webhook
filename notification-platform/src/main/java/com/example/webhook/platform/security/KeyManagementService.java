package com.example.webhook.platform.security;

import javax.crypto.SecretKey;

/** Boundary for local keys, Vault transit, or a cloud KMS envelope-key provider. */
public interface KeyManagementService {
    int activeVersion();
    SecretKey keyForVersion(int version);
}
