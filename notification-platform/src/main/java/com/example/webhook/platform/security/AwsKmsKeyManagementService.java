package com.example.webhook.platform.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;

/**
 * AWS KMS skeleton. Enable with {@code webhook.security.kms-provider=aws-kms}.
 * <p>
 * Required dependencies (add when wiring for real use):
 * <ul>
 *   <li>{@code software.amazon.awssdk:kms}</li>
 *   <li>Credentials via standard AWS provider chain / IRSA</li>
 *   <li>{@code webhook.security.aws.kms-key-id} (CMK ARN or alias)</li>
 * </ul>
 * Implement {@link #wrapDataKey(byte[])} / {@link #unwrapDataKey(int, byte[])} with
 * {@code Encrypt}/{@code Decrypt} (or GenerateDataKey) against the CMK.
 */
@Component
@ConditionalOnProperty(name = "webhook.security.kms-provider", havingValue = "aws-kms")
public class AwsKmsKeyManagementService implements KeyManagementService {
    @Override
    public int activeVersion() {
        throw new UnsupportedOperationException(
                "AwsKmsKeyManagementService is a skeleton — add AWS SDK KMS deps and wire CMK encrypt/decrypt");
    }

    @Override
    public SecretKey keyForVersion(int version) {
        throw new UnsupportedOperationException(
                "AWS KMS does not export KEKs; implement wrapDataKey/unwrapDataKey with Encrypt/Decrypt");
    }

    @Override
    public byte[] wrapDataKey(byte[] dek) {
        throw new UnsupportedOperationException(
                "AwsKmsKeyManagementService skeleton: implement KMS Encrypt for DEK wrap "
                        + "(deps: software.amazon.awssdk:kms, webhook.security.aws.kms-key-id)");
    }

    @Override
    public byte[] unwrapDataKey(int kekVersion, byte[] wrappedDek) {
        throw new UnsupportedOperationException(
                "AwsKmsKeyManagementService skeleton: implement KMS Decrypt for DEK unwrap "
                        + "(deps: software.amazon.awssdk:kms, webhook.security.aws.kms-key-id)");
    }
}
