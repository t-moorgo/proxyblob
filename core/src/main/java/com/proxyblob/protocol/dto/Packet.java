package com.proxyblob.protocol.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
public final class Packet {
    private byte command;
    private UUID connectionId;
    private byte[] data;
}