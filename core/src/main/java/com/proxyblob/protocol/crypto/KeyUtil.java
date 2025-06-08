package com.proxyblob.protocol.crypto;

import lombok.experimental.UtilityClass;
import org.bouncycastle.crypto.digests.SHA3Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import java.security.SecureRandom;

@UtilityClass
public class KeyUtil {

    public static final int NONCE_SIZE = 24;
    public static final int KEY_SIZE = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static KeyPair generateKeyPair() {
        byte[] privateBytes = new byte[KEY_SIZE];
        SECURE_RANDOM.nextBytes(privateBytes);

        // Manual clamping for X25519 (optional but for parity with Go)
        privateBytes[0] &= 248;
        privateBytes[31] &= 127;
        privateBytes[31] |= 64;

        X25519PrivateKeyParameters privateKey = new X25519PrivateKeyParameters(privateBytes, 0);

        X25519PublicKeyParameters publicKey = privateKey.generatePublicKey();
        return new KeyPair(privateKey, publicKey);
    }

    public static byte[] generateNonce() {
        byte[] nonce = new byte[NONCE_SIZE];
        SECURE_RANDOM.nextBytes(nonce);
        return nonce;
    }

    public static byte[] deriveSharedKey(X25519PrivateKeyParameters privateKey, X25519PublicKeyParameters peerPublicKey, byte[] nonce) {
        byte[] sharedSecret = new byte[KEY_SIZE];
        privateKey.generateSecret(peerPublicKey, sharedSecret, 0);

        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA3Digest(256));
        hkdf.init(new HKDFParameters(sharedSecret, nonce, null));

        byte[] derivedKey = new byte[KEY_SIZE];
        hkdf.generateBytes(derivedKey, 0, KEY_SIZE);

        return derivedKey;
    }

    public record KeyPair(X25519PrivateKeyParameters privateKey, X25519PublicKeyParameters publicKey) {
    }
}
