package com.proxyblob.transport.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Duration;

@Data
@AllArgsConstructor
public class DelayResult {
    private Duration nextDelay;
    private byte errorCode;
}
