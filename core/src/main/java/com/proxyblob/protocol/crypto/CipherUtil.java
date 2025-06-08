package com.proxyblob.protocol.crypto;

import lombok.experimental.UtilityClass;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.security.SecureRandom;

@UtilityClass
public class CipherUtil {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int NONCE_SIZE = KeyUtil.NONCE_SIZE;
    private static final int MAC_SIZE_BITS = 128;

    public static byte[] encrypt(byte[] key, byte[] plaintext) throws Exception {
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

        return result;
    }

    public static byte[] decrypt(byte[] key, byte[] ciphertext) throws Exception {
        if (ciphertext.length < NONCE_SIZE) {
            throw new IllegalArgumentException("Ciphertext too short");
        }

        byte[] nonce = new byte[NONCE_SIZE];
        byte[] encrypted = new byte[ciphertext.length - NONCE_SIZE];

        System.arraycopy(ciphertext, 0, nonce, 0, NONCE_SIZE);
        System.arraycopy(ciphertext, NONCE_SIZE, encrypted, 0, encrypted.length);

        ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
        cipher.init(false, new AEADParameters(new KeyParameter(key), MAC_SIZE_BITS, nonce));

        byte[] output = new byte[cipher.getOutputSize(encrypted.length)];
        int len = cipher.processBytes(encrypted, 0, encrypted.length, output, 0);
        cipher.doFinal(output, len);

        return output;
    }

    public static byte[] xor(byte[] data, byte[] key) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return result;
    }
}
