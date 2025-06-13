package com.proxyblob.proxy.socks.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public final class ReceiveResult {
    private byte[] data;
    private byte errorCode;
}
