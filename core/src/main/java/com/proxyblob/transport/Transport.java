package com.proxyblob.transport;

import com.proxyblob.proxy.socks.dto.ReceiveResult;

public interface Transport {

    byte send(byte[] data);

    ReceiveResult receive();

    boolean isClosed(byte errorCode);
}
