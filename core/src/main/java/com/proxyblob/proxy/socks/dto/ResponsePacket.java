package com.proxyblob.proxy.socks.dto;

import lombok.Builder;
import lombok.Getter;

import java.net.InetSocketAddress;

@Getter
@Builder
public final class ResponsePacket {
    private byte[] data;
    private InetSocketAddress addr;
}
