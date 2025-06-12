package com.proxyblob.transport.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CheckResult {
    private boolean isEmpty;
    private byte errorCode;
}
