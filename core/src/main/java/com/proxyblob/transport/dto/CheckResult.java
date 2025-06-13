package com.proxyblob.transport.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public final class CheckResult {
    private boolean isEmpty;
    private byte errorCode;
}
