package com.proxyblob.proxy.socks;

import com.proxyblob.protocol.error.ProtocolError;
import com.proxyblob.protocol.model.Connection;

public class SocksBindHandler {
    public byte handle(Connection conn, byte[] data) {
        return ProtocolError.UNSUPPORTED_COMMAND.getCode();
    }
}