package com.proxyblob.proxy.socks.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.net.InetSocketAddress;
import java.time.Instant;

@Data
@AllArgsConstructor
public class TargetInfo {
    private InetSocketAddress addr;
    private Instant lastActive;
}
