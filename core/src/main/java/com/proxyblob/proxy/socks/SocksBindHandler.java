package com.proxyblob.proxy.socks;

import com.proxyblob.protocol.ProtocolError;
import com.proxyblob.protocol.Connection;

public class SocksBindHandler {
    public byte handle(Connection conn, byte[] data) {
        return ProtocolError.UNSUPPORTED_COMMAND.getCode();
    }
}