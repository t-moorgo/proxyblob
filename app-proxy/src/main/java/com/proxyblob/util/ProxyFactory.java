package com.proxyblob.util;

import com.azure.storage.blob.BlobContainerClient;
import com.proxyblob.protocol.handler.BaseHandler;
import com.proxyblob.proxy.PacketHandler;
import com.proxyblob.proxy.server.ProxyServer;
import com.proxyblob.proxy.socks.SocksHandler;
import com.proxyblob.transport.BlobTransportFactory;
import com.proxyblob.transport.Transport;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ProxyFactory {
    public static ProxyServer create(BlobContainerClient container) {
        Transport transport = BlobTransportFactory.create(container, "response", "request");

        BaseHandler handler = new BaseHandler(transport);
        PacketHandler socksHandler = new SocksHandler(handler);
        handler.registerPacketHandler(socksHandler);

        return new ProxyServer(handler);
    }
}
