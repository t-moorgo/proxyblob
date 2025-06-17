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
import static com.proxyblob.protocol.CryptoUtil.KEY_SIZE;
import static com.proxyblob.protocol.CryptoUtil.NONCE_SIZE;
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
        System.out.println("[SocksHandler] Starting SOCKS handler");
        context.getGeneralExecutor().submit(this::receiveLoop);
    }

    @Override
    public void stop() {
        System.out.println("[SocksHandler] Stopping SOCKS handler");
        baseHandler.closeAllConnections();
        context.stop();
    }

    @Override
    public void receiveLoop() {
        baseHandler.receiveLoop();
    }

    @Override
    public byte onNew(UUID connectionId, byte[] data) {
        System.out.println("[SocksHandler] onNew: " + connectionId);

        if (baseHandler.getConnections().containsKey(connectionId)) {
            System.out.println("[SocksHandler] Connection already exists");
            return ErrConnectionExists;
        }

        if (data.length != CryptoUtil.NONCE_SIZE + CryptoUtil.KEY_SIZE) {
            System.out.println("[SocksHandler] Invalid NEW packet data length: " + data.length);
            return ErrInvalidPacket;
        }

        Connection conn = new Connection(connectionId);
        conn.setSecretKey(data); // <- Сохраняем nonce + publicKey от прокси
        baseHandler.getConnections().put(connectionId, conn);

        System.out.println("[SocksHandler] Received key exchange data (nonce + serverPubKey)");

        byte errCode = baseHandler.sendConnAck(connectionId);
        if (errCode != ErrNone) {
            System.out.println("[SocksHandler] Failed to send ConnAck, error: " + errCode);
            return errCode;
        }

        context.getGeneralExecutor().submit(() -> processConnection(conn));
        return ErrNone;
    }

    @Override
    public byte onAck(UUID connectionId, byte[] data) {
        System.out.println("[SocksHandler] onAck called unexpectedly");
        return ErrUnexpectedPacket;
    }

    @Override
    public byte onData(UUID connectionId, byte[] data) {
        Connection conn = baseHandler.getConnections().get(connectionId);
        if (conn == null) {
            System.out.println("[SocksHandler] onData: connection not found: " + connectionId);
            return ErrConnectionNotFound;
        }

        CryptoResult result = CryptoUtil.decrypt(conn.getSecretKey(), data);
        if (result.getStatus() != ErrNone) {
            System.out.println("[SocksHandler] Failed to decrypt data, error: " + result.getStatus());
            baseHandler.sendClose(connectionId, ErrInvalidCrypto);
            return ErrInvalidCrypto;
        }

        if (context.isStopped()) {
            System.out.println("[SocksHandler] Context stopped during onData");
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
        System.out.println("[SocksHandler] onClose: " + connectionId + ", error=" + errorCode);
        Connection value = baseHandler.getConnections().get(connectionId);
        if (value == null) {
            return ErrNone;
        }

        value.close();
        baseHandler.getConnections().remove(connectionId);

        return ErrNone;
    }

    private void processConnection(Connection conn) {
        System.out.println("[SocksHandler] Processing new SOCKS5 connection: " + conn.getId());

        byte errCode;

        errCode = handleAuthNegotiation(conn);
        if (errCode != ErrNone) {
            System.out.println("[SocksHandler] Auth negotiation failed: " + errCode);
            baseHandler.sendClose(conn.getId(), errCode);
            return;
        }

        errCode = handleCommand(conn);
        if (errCode != ErrNone) {
            System.out.println("[SocksHandler] Command handling failed: " + errCode);
            baseHandler.sendClose(conn.getId(), errCode);
            return;
        }

        errCode = handleDataTransfer(conn);
        if (errCode != ErrNone) {
            System.out.println("[SocksHandler] Data transfer finished with error: " + errCode);
            baseHandler.sendClose(conn.getId(), errCode);
        }
    }

    private byte handleAuthNegotiation(Connection conn) {
        System.out.println("[SocksHandler] Starting authentication negotiation");

        try {
            byte[] methods = conn.getReadBuffer().take();

            System.out.print("[SocksHandler] Received auth methods: ");
            for (byte b : methods) {
                System.out.printf("0x%02X ", b);
            }
            System.out.println();

            boolean noAuthSupported = false;
            for (byte b : methods) {
                if (b == NoAuth) {
                    noAuthSupported = true;
                    break;
                }
            }

            if (!noAuthSupported) {
                System.out.println("[SocksHandler] No supported auth method");
                SocksErrorUtil.sendError(baseHandler, conn, ErrAuthFailed);
                return ErrAuthFailed;
            }

            byte[] response = new byte[]{Version5, NoAuth};
            System.out.println("[SocksHandler] Responding with NO_AUTH");
            return baseHandler.sendData(conn.getId(), response);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("[SocksHandler] Auth negotiation interrupted");

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
        System.out.println("[SocksHandler] Waiting for command");

        try {
            byte[] cmdData = conn.getReadBuffer().take();

            if (cmdData.length < 4) {
                System.out.println("[SocksHandler] Invalid command data");
                SocksErrorUtil.sendError(baseHandler, conn, ErrInvalidPacket);
                return ErrInvalidPacket;
            }

            if (cmdData[0] != Version5) {
                System.out.println("[SocksHandler] Unsupported SOCKS version: " + cmdData[0]);
                SocksErrorUtil.sendError(baseHandler, conn, ErrInvalidSocksVersion);
                return ErrInvalidSocksVersion;
            }

            byte command = cmdData[1];
            System.out.println("[SocksHandler] Received command: " + command);

            return switch (command) {
                case Connect -> {
                    System.out.println("[SocksHandler] Handling CONNECT command");
                    yield connectHandler.handle(conn, cmdData);
                }
                case Bind -> {
                    System.out.println("[SocksHandler] Handling BIND command");
                    yield bindHandler.handle(conn, cmdData);
                }
                case UDPAssociate -> {
                    System.out.println("[SocksHandler] Handling UDP ASSOCIATE command");
                    yield udpHandler.handle(conn);
                }
                default -> {
                    System.out.println("[SocksHandler] Unsupported command: " + command);
                    SocksErrorUtil.sendError(baseHandler, conn, ErrUnsupportedCommand);
                    yield ErrUnsupportedCommand;
                }
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("[SocksHandler] Command handling interrupted");

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
        System.out.println("[SocksHandler] Entering data transfer loop");

        try {
            conn.awaitClose();
            System.out.println("[SocksHandler] Connection closed cleanly: " + conn.getId());
            return ErrNone;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("[SocksHandler] Interrupted while waiting for close");
            return ErrHandlerStopped;
        }
    }
}
