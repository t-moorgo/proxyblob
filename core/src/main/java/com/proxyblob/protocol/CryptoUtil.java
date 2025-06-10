package com.proxyblob.protocol;

import lombok.experimental.UtilityClass;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.digests.SHA3Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import java.security.SecureRandom;

@UtilityClass
public class CryptoUtil {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAC_SIZE_BITS = 128;

    public static final int NONCE_SIZE = 24;
    public static final int KEY_SIZE = 32;

    public enum CryptoStatus {
        OK,
        INVALID_CRYPTO
    }

    public record CryptoResult(byte[] data, CryptoStatus status) {
        public static CryptoResult error() {
            return new CryptoResult(null, CryptoStatus.INVALID_CRYPTO);
        }

        public static CryptoResult ok(byte[] data) {
            return new CryptoResult(data, CryptoStatus.OK);
        }
    }

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

    public static byte[] deriveKey(X25519PrivateKeyParameters privateKey, X25519PublicKeyParameters peerPublicKey, byte[] nonce) {
        byte[] sharedSecret = new byte[KEY_SIZE];
        privateKey.generateSecret(peerPublicKey, sharedSecret, 0);

        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA3Digest(256));
        hkdf.init(new HKDFParameters(sharedSecret, nonce, null));

        byte[] derivedKey = new byte[KEY_SIZE];
        hkdf.generateBytes(derivedKey, 0, KEY_SIZE);

        return derivedKey;
    }

    public static CryptoResult encrypt(byte[] key, byte[] plaintext) {
        try {
            byte[] nonce = new byte[NONCE_SIZE];
            SECURE_RANDOM.nextBytes(nonce);

            ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
            cipher.init(true, new AEADParameters(new KeyParameter(key), MAC_SIZE_BITS, nonce));

            byte[] output = new byte[cipher.getOutputSize(plaintext.length)];
            int len = cipher.processBytes(plaintext, 0, plaintext.length, output, 0);
            cipher.doFinal(output, len);

            byte[] result = new byte[nonce.length + output.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length);
            System.arraycopy(output, 0, result, nonce.length, output.length);

            return CryptoResult.ok(result);
        } catch (Exception e) {
            return CryptoResult.error();
        }
    }

    public static CryptoResult decrypt(byte[] key, byte[] ciphertext) {
        if (ciphertext.length < NONCE_SIZE) {
            return CryptoResult.error();
        }

        try {
            byte[] nonce = new byte[NONCE_SIZE];
            byte[] encrypted = new byte[ciphertext.length - NONCE_SIZE];

            System.arraycopy(ciphertext, 0, nonce, 0, NONCE_SIZE);
            System.arraycopy(ciphertext, NONCE_SIZE, encrypted, 0, encrypted.length);

            ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
            cipher.init(false, new AEADParameters(new KeyParameter(key), MAC_SIZE_BITS, nonce));

            byte[] output = new byte[cipher.getOutputSize(encrypted.length)];
            int len = cipher.processBytes(encrypted, 0, encrypted.length, output, 0);
            cipher.doFinal(output, len);

            return CryptoResult.ok(output);
        } catch (InvalidCipherTextException e) {
            return CryptoResult.error();
        }
    }

    public static byte[] xor(byte[] data, byte[] key) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return result;
    }

    public record KeyPair(X25519PrivateKeyParameters privateKey, X25519PublicKeyParameters publicKey) {
    }
}
