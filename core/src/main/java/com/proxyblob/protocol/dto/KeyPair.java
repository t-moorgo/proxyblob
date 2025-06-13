package com.proxyblob.protocol.dto;

import lombok.Builder;
import lombok.Getter;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

@Getter
@Builder
public final class KeyPair {
    private X25519PrivateKeyParameters privateKey;
    private X25519PublicKeyParameters publicKey;
}
