package com.proxyblob.proxy;

import java.util.UUID;

public interface PacketHandler {

    void start(String address);

    void stop();

    void receiveLoop();

    byte onNew(UUID connectionId, byte[] data);

    byte onAck(UUID connectionId, byte[] data);

    byte onData(UUID connectionId, byte[] data);

    byte onClose(UUID connectionId, byte errorCode);
}
