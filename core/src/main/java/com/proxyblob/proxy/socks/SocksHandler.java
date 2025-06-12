package com.proxyblob.proxy.socks;

import com.proxyblob.context.AppContext;
import com.proxyblob.protocol.BaseHandler;
import com.proxyblob.protocol.Connection;
import com.proxyblob.protocol.CryptoUtil;
import com.proxyblob.protocol.dto.CryptoResult;
import com.proxyblob.proxy.PacketHandler;
import com.proxyblob.transport.Transport;

import java.util.UUID;

import static com.proxyblob.errorcodes.ErrorCodes.ErrAuthFailed;
import static com.proxyblob.errorcodes.ErrorCodes.ErrConnectionClosed;
import static com.proxyblob.errorcodes.ErrorCodes.ErrConnectionExists;
import static com.proxyblob.errorcodes.ErrorCodes.ErrConnectionNotFound;
import static com.proxyblob.errorcodes.ErrorCodes.ErrHandlerStopped;
import static com.proxyblob.errorcodes.ErrorCodes.ErrInvalidCrypto;
import static com.proxyblob.errorcodes.ErrorCodes.ErrInvalidPacket;
import static com.proxyblob.errorcodes.ErrorCodes.ErrInvalidSocksVersion;
import static com.proxyblob.errorcodes.ErrorCodes.ErrNone;
import static com.proxyblob.errorcodes.ErrorCodes.ErrUnexpectedPacket;
import static com.proxyblob.errorcodes.ErrorCodes.ErrUnsupportedCommand;
import static com.proxyblob.proxy.socks.SocksConstants.Bind;
import static com.proxyblob.proxy.socks.SocksConstants.Connect;
import static com.proxyblob.proxy.socks.SocksConstants.NoAuth;
import static com.proxyblob.proxy.socks.SocksConstants.UDPAssociate;
import static com.proxyblob.proxy.socks.SocksConstants.Version5;

public class SocksHandler implements PacketHandler {

    private final BaseHandler baseHandler;
    private final AppContext context;
    private final SocksBindHandler bindHandler;
    private final SocksConnectHandler connectHandler;
    private final SocksUDPHandler udpHandler;

    public SocksHandler(Transport transport, AppContext context) {
        this.context = context;
        this.baseHandler = new BaseHandler(transport, this, context);
        this.bindHandler = new SocksBindHandler(baseHandler);
        this.connectHandler = new SocksConnectHandler(baseHandler);
        this.udpHandler = new SocksUDPHandler(baseHandler);
    }

    @Override
    public void start(String address) {
        context.getGeneralExecutor().submit(this::receiveLoop);
    }

    @Override
    public void stop() {
        baseHandler.closeAllConnections();
        context.stop();
    }

    @Override
    public void receiveLoop() {
        baseHandler.receiveLoop();
    }

    @Override
    public byte onAck(UUID connectionId, byte[] data) {
        return ErrUnexpectedPacket;
    }

    @Override
    public byte onNew(UUID connectionId, byte[] data) {
        if (baseHandler.getConnections().containsKey(connectionId)) {
            return ErrConnectionExists;
        }

        Connection conn = new Connection(connectionId);
        baseHandler.getConnections().put(connectionId, conn);

        // Если data содержит nonce (24 байта) + server public key (32 байта)
        if (data.length >= 24 + 32) {
            byte[] tmp = new byte[56];
            System.arraycopy(data, 0, tmp, 0, 56);
            conn.setSecretKey(tmp);
        }

        byte errCode = baseHandler.sendConnAck(connectionId);
        if (errCode != ErrNone) {
            return errCode;
        }

        context.getGeneralExecutor().submit(() -> processConnection(conn));
        return ErrNone;
    }

    @Override
    public byte onData(UUID connectionId, byte[] data) {
        Connection conn = baseHandler.getConnections().get(connectionId);
        if (conn == null) {
            return ErrConnectionNotFound;
        }

        CryptoResult result = CryptoUtil.decrypt(conn.getSecretKey(), data);
        if (result.getStatus() != ErrNone) {
            baseHandler.sendClose(connectionId, ErrInvalidCrypto);
            return ErrInvalidCrypto;
        }

        if (context.isStopped()) {
            return ErrConnectionClosed;
        }

        try {
            conn.getReadBuffer().put(result.getData());
            return ErrNone;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ErrHandlerStopped;
        }
    }

    @Override
    public byte onClose(UUID connectionId, byte errorCode) {
        Connection value = baseHandler.getConnections().get(connectionId);
        if (value == null) {
            return ErrNone;
        }

        value.close();
        baseHandler.getConnections().remove(connectionId);

        return ErrNone;
    }


    private void processConnection(Connection conn) {
        byte errCode;

        errCode = handleAuthNegotiation(conn);
        if (errCode != ErrNone) {
            baseHandler.sendClose(conn.getId(), errCode);
            return;
        }

        errCode = handleCommand(conn);
        if (errCode != ErrNone) {
            baseHandler.sendClose(conn.getId(), errCode);
            return;
        }

        errCode = handleDataTransfer(conn);
        if (errCode != ErrNone) {
            baseHandler.sendClose(conn.getId(), errCode);
        }
    }

    private byte handleAuthNegotiation(Connection conn) {
        try {
            byte[] methods = conn.getReadBuffer().take();

            boolean noAuthSupported = false;
            for (byte b : methods) {
                if (b == NoAuth) {
                    noAuthSupported = true;
                    break;
                }
            }

            if (!noAuthSupported) {
                SocksErrorUtil.sendError(baseHandler, conn, ErrAuthFailed);
                return ErrAuthFailed;
            }

            byte[] response = new byte[]{Version5, NoAuth};
            return baseHandler.sendData(conn.getId(), response);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            if (conn.getClosed().get()) {
                return ErrConnectionClosed;
            }

            if (context.isStopped()) {
                return ErrHandlerStopped;
            }

            return ErrHandlerStopped;
        }
    }

    private byte handleCommand(Connection conn) {
        try {
            byte[] cmdData = conn.getReadBuffer().take();

            if (cmdData.length < 4) {
                SocksErrorUtil.sendError(baseHandler, conn, ErrInvalidPacket);
                return ErrInvalidPacket;
            }

            if (cmdData[0] != Version5) {
                SocksErrorUtil.sendError(baseHandler, conn, ErrInvalidSocksVersion);
                return ErrInvalidSocksVersion;
            }

            byte command = cmdData[1];
            return switch (command) {
                case Connect -> connectHandler.handle(conn, cmdData);
                case Bind -> bindHandler.handle(conn, cmdData);
                case UDPAssociate -> udpHandler.handle(conn);
                default -> {
                    SocksErrorUtil.sendError(baseHandler, conn, ErrUnsupportedCommand);
                    yield ErrUnsupportedCommand;
                }
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            if (conn.getClosed().get()) {
                return ErrConnectionClosed;
            }

            if (context.isStopped()) {
                return ErrHandlerStopped;
            }

            return ErrHandlerStopped;
        }
    }

    private byte handleDataTransfer(Connection conn) {
        try {
            conn.awaitClose();
            return ErrNone;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ErrHandlerStopped;
        }
    }
}