package com.proxyblob.proxy.socks.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.net.InetSocketAddress;

@Data
@AllArgsConstructor
public class ResponsePacket {
    byte[] data;
    InetSocketAddress addr;
}
