package com.proxyblob.proxy.socks.dto;

import lombok.Builder;
import lombok.Getter;

import java.net.InetSocketAddress;
import java.time.Instant;

@Getter
@Builder
public final class TargetInfo {
    private InetSocketAddress addr;
    private Instant lastActive;
}
