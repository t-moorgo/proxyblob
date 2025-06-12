package com.proxyblob.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CryptoResult {
    private byte[] data;
    private byte status;
}
