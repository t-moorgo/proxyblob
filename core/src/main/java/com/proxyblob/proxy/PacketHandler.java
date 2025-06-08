package com.proxyblob.proxy;

import com.proxyblob.protocol.error.ProtocolError;

import java.util.UUID;

public interface PacketHandler {

    void start(String address);

    void stop();

    void receiveLoop();

    ProtocolError onNew(UUID connectionId, byte[] data);

    ProtocolError onAck(UUID connectionId, byte[] data);

    ProtocolError onData(UUID connectionId, byte[] data);

    ProtocolError onClose(UUID connectionId, byte errorCode);
}
