package com.proxyblob.protocol.dto;

import com.proxyblob.protocol.CryptoUtil;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CryptoResult {
    private byte[] data;
    private CryptoStatus status;
}
