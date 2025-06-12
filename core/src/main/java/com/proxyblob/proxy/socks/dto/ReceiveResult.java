package com.proxyblob.proxy.socks.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ReceiveResult {
    private byte[] data;
    private byte errorCode;
}
