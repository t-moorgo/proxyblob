package com.proxyblob.transport;

import com.proxyblob.proxy.socks.dto.ReceiveResult;

import java.util.concurrent.atomic.AtomicBoolean;

public interface Transport {

    // Error codes (matching transport.go)
    byte ErrNone = 0;
    byte ErrContextCanceled = 2;

    byte ErrTransportClosed = 20;
    byte ErrTransportTimeout = 21;
    byte ErrTransportError = 22;

    byte send(byte[] data);

    ReceiveResult receive();

    boolean isClosed(byte errorCode);
}
