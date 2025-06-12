package com.proxyblob.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

@Data
@AllArgsConstructor
public class KeyPair {
    private X25519PrivateKeyParameters privateKey;
    private X25519PublicKeyParameters publicKey;
}
