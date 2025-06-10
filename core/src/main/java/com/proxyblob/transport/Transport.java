package com.proxyblob.transport;

import java.util.concurrent.atomic.AtomicBoolean;

public interface Transport {

    // Error codes (matching transport.go)
    byte ErrNone = 0;
    byte ErrContextCanceled = 2;

    byte ErrTransportClosed = 20;
    byte ErrTransportTimeout = 21;
    byte ErrTransportError = 22;

    byte send(AtomicBoolean cancelFlag, byte[] data);

    ReceiveResult receive(AtomicBoolean cancelFlag);

    boolean isClosed(byte errorCode);

    record ReceiveResult(byte[] data, byte errorCode) {}
}
