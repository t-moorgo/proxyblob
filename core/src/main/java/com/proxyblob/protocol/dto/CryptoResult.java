package com.proxyblob.protocol.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public final class CryptoResult {
    private byte[] data;
    private byte status;
}
