package com.proxyblob.transport.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;

@Getter
@Builder
public final class DelayResult {
    private Duration nextDelay;
    private byte errorCode;
}
