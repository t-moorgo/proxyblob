package com.proxyblob.proxy.socks.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public final class ParsedAddress {
    private String hostAndPort;
    private int consumedBytes;
    private byte errorCode;
}
