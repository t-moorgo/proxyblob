package com.proxyblob.protocol;

import com.proxyblob.protocol.dto.CryptoResult;
import com.proxyblob.protocol.dto.KeyPair;
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

import static com.proxyblob.errorcodes.ErrorCodes.ErrInvalidCrypto;
import static com.proxyblob.errorcodes.ErrorCodes.ErrNone;

@UtilityClass
public class CryptoUtil {

    public final int NONCE_SIZE = 24;
    public final int KEY_SIZE = 32;

    private final int MAC_SIZE_BITS = 128;
    private final byte CLAMP_MASK_FIRST_BYTE = (byte) 248;
    private final byte CLAMP_MASK_LAST_BYTE = (byte) 127;
    private final byte CLAMP_OR_LAST_BYTE = (byte) 64;
    private final SecureRandom SECURE_RANDOM = new SecureRandom();

    public KeyPair generateKeyPair() {
        byte[] privateBytes = new byte[KEY_SIZE];
        SECURE_RANDOM.nextBytes(privateBytes);

        privateBytes[0] &= CLAMP_MASK_FIRST_BYTE;
        privateBytes[31] &= CLAMP_MASK_LAST_BYTE;
        privateBytes[31] |= CLAMP_OR_LAST_BYTE;

        X25519PrivateKeyParameters privateKey = new X25519PrivateKeyParameters(privateBytes, 0);
        X25519PublicKeyParameters publicKey = privateKey.generatePublicKey();

        return KeyPair.builder()
                .privateKey(privateKey)
                .publicKey(publicKey)
                .build();
    }

    public byte[] generateNonce() {
        byte[] nonce = new byte[NONCE_SIZE];
        SECURE_RANDOM.nextBytes(nonce);
        return nonce;
    }

    public byte[] deriveKey(X25519PrivateKeyParameters privateKey, X25519PublicKeyParameters peerPublicKey, byte[] nonce) {
        byte[] sharedSecret = new byte[KEY_SIZE];
        privateKey.generateSecret(peerPublicKey, sharedSecret, 0);

        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA3Digest(256));
        hkdf.init(new HKDFParameters(sharedSecret, nonce, null));

        byte[] derivedKey = new byte[KEY_SIZE];
        hkdf.generateBytes(derivedKey, 0, KEY_SIZE);

        return derivedKey;
    }

    public CryptoResult encrypt(byte[] key, byte[] plaintext) {
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

            return ok(result);
        } catch (Exception e) {
            return error();
        }
    }

    public CryptoResult decrypt(byte[] key, byte[] ciphertext) {
        if (ciphertext.length < NONCE_SIZE) {
            return error();
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

            return ok(output);
        } catch (InvalidCipherTextException e) {
            return error();
        }
    }

    public byte[] xor(byte[] data, byte[] key) {
        for (int i = 0; i < data.length; i++) {
            data[i] ^= key[i % key.length];
        }
        return data;
    }

    private CryptoResult error() {
        return CryptoResult.builder()
                .data(null)
                .status(ErrInvalidCrypto)
                .build();
    }

    private CryptoResult ok(byte[] data) {
        return CryptoResult.builder()
                .data(data)
                .status(ErrNone)
                .build();
    }
}
