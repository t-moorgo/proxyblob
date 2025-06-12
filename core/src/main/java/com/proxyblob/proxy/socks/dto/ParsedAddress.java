package com.proxyblob.proxy.socks.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ParsedAddress {
    private String hostAndPort;
    private int consumedBytes;
    private byte errorCode;
}
