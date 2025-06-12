package com.proxyblob.proxy.socks;

import com.proxyblob.protocol.BaseHandler;
import com.proxyblob.protocol.Connection;
import lombok.RequiredArgsConstructor;

import static com.proxyblob.errorcodes.ErrorCodes.ErrUnsupportedCommand;

@RequiredArgsConstructor
public class SocksBindHandler {

    private final BaseHandler baseHandler;

    public byte handle(Connection conn, byte[] data) {
        return ErrUnsupportedCommand;
    }
}