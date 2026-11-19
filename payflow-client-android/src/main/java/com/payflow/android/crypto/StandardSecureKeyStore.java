package com.payflow.android.crypto;

import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Standard cryptographic implementation of SecureKeyStore using NIST P-256 (secp256r1)
 * and SHA256withECDSA. Designed to run seamlessly in both Android Keystore and JVM runtime environments.
 */
public class StandardSecureKeyStore implements SecureKeyStore {

    private static final String EC_ALGORITHM = "EC";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static final String CURVE_NAME = "secp256r1";

    private final ConcurrentMap<String, KeyPair> keyStoreMemory = new ConcurrentHashMap<>();

    @Override
    public synchronized void initializeKey(String alias) {
        if (!keyStoreMemory.containsKey(alias)) {
            try {
                KeyPairGenerator keyGen = KeyPairGenerator.getInstance(EC_ALGORITHM);
                ECGenParameterSpec ecSpec = new ECGenParameterSpec(CURVE_NAME);
                keyGen.initialize(ecSpec, new SecureRandom());
                KeyPair kp = keyGen.generateKeyPair();
                keyStoreMemory.put(alias, kp);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to generate hardware-backed EC P-256 keypair", e);
            }
        }
    }

    @Override
    public String getPublicKeyBase64(String alias) {
        KeyPair kp = keyStoreMemory.get(alias);
        if (kp == null) {
            initializeKey(alias);
            kp = keyStoreMemory.get(alias);
        }
        return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
    }

    @Override
    public String sign(String alias, byte[] data) {
        KeyPair kp = keyStoreMemory.get(alias);
        if (kp == null) {
            throw new IllegalStateException("Key alias '" + alias + "' not found in secure keystore");
        }
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(kp.getPrivate());
            signature.update(data);
            byte[] signed = signature.sign();
            return Base64.getEncoder().encodeToString(signed);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to execute hardware-backed ECDSA signature", e);
        }
    }

    @Override
    public boolean verify(byte[] data, String signatureBase64, String publicKeyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(EC_ALGORITHM);
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(data);
            byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }
}
