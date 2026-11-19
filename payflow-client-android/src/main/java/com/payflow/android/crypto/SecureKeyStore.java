package com.payflow.android.crypto;

import java.security.PublicKey;

/**
 * Interface representing hardware-backed Keystore operations (Android Keystore / TEE / StrongBox).
 */
public interface SecureKeyStore {

    /**
     * Generates or retrieves an EC P-256 keypair bound to hardware.
     */
    void initializeKey(String alias);

    /**
     * Returns Base64-encoded X.509 public key for device enrollment.
     */
    String getPublicKeyBase64(String alias);

    /**
     * Signs data using the hardware-protected private key.
     */
    String sign(String alias, byte[] data);

    /**
     * Verifies ECDSA signature using a public key.
     */
    boolean verify(byte[] data, String signatureBase64, String publicKeyBase64);
}
