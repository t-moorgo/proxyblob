package com.proxyblob.proxy.socks;

import com.proxyblob.protocol.crypto.CipherUtil;
import com.proxyblob.protocol.error.ProtocolError;
import com.proxyblob.protocol.handler.BaseHandler;
import com.proxyblob.protocol.model.Connection;
import com.proxyblob.proxy.PacketHandler;
import lombok.RequiredArgsConstructor;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RequiredArgsConstructor
public class SocksHandler implements PacketHandler {

    private final BaseHandler baseHandler;
    private final SocksConnectHandler connectHandler;
    private final SocksUDPHandler udpHandler;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    public SocksHandler(BaseHandler baseHandler) {
        this.baseHandler = baseHandler;
        this.connectHandler = new SocksConnectHandler(baseHandler);
        this.udpHandler = new SocksUDPHandler(baseHandler);
    }

    @Override
    public void start(String address) {
        executor.submit(baseHandler::receiveLoop);
    }

    @Override
    public void stop() {
        baseHandler.closeAllConnections();
        baseHandler.stop();
    }

    @Override
    public void receiveLoop() {
        baseHandler.receiveLoop();
    }

    @Override
    public ProtocolError onNew(UUID connectionId, byte[] data) {
        if (baseHandler.getConnection(connectionId) != null) {
            return ProtocolError.CONNECTION_EXISTS;
        }

        Connection conn = new Connection(connectionId);
        baseHandler.addConnection(conn);

        if (data.length >= 56) {
            byte[] tmp = new byte[56];
            System.arraycopy(data, 0, tmp, 0, 56);
            conn.setSecretKey(tmp);
        }

        ProtocolError err = baseHandler.sendConnAck(connectionId, data);
        if (err != ProtocolError.NONE) {
            return err;
        }

        executor.submit(() -> processConnection(conn));
        return ProtocolError.NONE;
    }

    @Override
    public ProtocolError onAck(UUID connectionId, byte[] data) {
        return ProtocolError.UNEXPECTED_PACKET;
    }

    @Override
    public ProtocolError onData(UUID connectionId, byte[] encryptedData) {
        Connection conn = baseHandler.getConnection(connectionId);
        if (conn == null) return ProtocolError.CONNECTION_NOT_FOUND;

        try {
            byte[] decrypted = CipherUtil.decrypt(conn.getSecretKey(), encryptedData);
            conn.getReadBuffer().put(decrypted);
            return ProtocolError.NONE;
        } catch (Exception e) {
            baseHandler.sendClose(connectionId, ProtocolError.INVALID_CRYPTO);
            return ProtocolError.INVALID_CRYPTO;
        }
    }

    @Override
    public ProtocolError onClose(UUID connectionId, byte errorCode) {
        Connection conn = baseHandler.getConnection(connectionId);
        if (conn != null) {
            conn.close();
            baseHandler.removeConnection(connectionId);
        }
        return ProtocolError.NONE;
    }

    private void processConnection(Connection conn) {
        ProtocolError err;

        err = handleAuthNegotiation(conn);
        if (err != ProtocolError.NONE) {
            baseHandler.sendClose(conn.getId(), err);
            return;
        }

        err = handleCommand(conn);
        if (err != ProtocolError.NONE) {
            baseHandler.sendClose(conn.getId(), err);
            return;
        }

        handleDataTransfer(conn);
    }

    private ProtocolError handleAuthNegotiation(Connection conn) {
        try {
            byte[] methods = conn.getReadBuffer().take();
            boolean noAuthSupported = false;
            for (byte b : methods) {
                if (b == SocksConstants.NO_AUTH) {
                    noAuthSupported = true;
                    break;
                }
            }

            if (!noAuthSupported) {
                sendError(conn, ProtocolError.AUTH_FAILED);
                return ProtocolError.AUTH_FAILED;
            }

            return baseHandler.sendData(conn.getId(), new byte[]{SocksConstants.VERSION_5, SocksConstants.NO_AUTH});
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProtocolError.HANDLER_STOPPED;
        }
    }

    private ProtocolError handleCommand(Connection conn) {
        try {
            byte[] cmdData = conn.getReadBuffer().take();

            if (cmdData.length < 4) {
                sendError(conn, ProtocolError.INVALID_PACKET);
                return ProtocolError.INVALID_PACKET;
            }

            if (cmdData[0] != SocksConstants.VERSION_5) {
                sendError(conn, ProtocolError.INVALID_SOCKS_VERSION);
                return ProtocolError.INVALID_SOCKS_VERSION;
            }

            byte command = cmdData[1];
            return switch (command) {
                case SocksConstants.CONNECT -> handleConnect(conn, cmdData);
                case SocksConstants.BIND -> handleBind(conn, cmdData);
                case SocksConstants.UDP_ASSOCIATE -> handleUDPAssociate(conn);
                default -> {
                    sendError(conn, ProtocolError.UNSUPPORTED_COMMAND);
                    yield ProtocolError.UNSUPPORTED_COMMAND;
                }
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProtocolError.HANDLER_STOPPED;
        }
    }

    private void handleDataTransfer(Connection conn) {
        while (conn != null && !conn.isClosed()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void sendError(Connection conn, ProtocolError errCode) {
        byte reply = switch (errCode) {
            case NONE -> SocksConstants.SUCCEEDED;
            case NETWORK_UNREACHABLE -> SocksConstants.NETWORK_UNREACHABLE;
            case HOST_UNREACHABLE -> SocksConstants.HOST_UNREACHABLE;
            case CONNECTION_REFUSED -> SocksConstants.CONNECTION_REFUSED;
            case TTL_EXPIRED -> SocksConstants.TTL_EXPIRED;
            case UNSUPPORTED_COMMAND -> SocksConstants.COMMAND_NOT_SUPPORTED;
            case ADDRESS_NOT_SUPPORTED -> SocksConstants.ADDRESS_TYPE_NOT_SUPPORTED;
            case AUTH_FAILED -> SocksConstants.NO_ACCEPTABLE_METHODS;
            default -> SocksConstants.GENERAL_FAILURE;
        };

        byte[] response = {
                SocksConstants.VERSION_5,
                reply,
                0x00,
                SocksConstants.IPV4,
                0, 0, 0, 0, 0, 0
        };

        baseHandler.sendData(conn.getId(), response);
    }

    private ProtocolError handleConnect(Connection conn, byte[] cmdData) {
        return ProtocolError.fromByte(connectHandler.handle(conn, cmdData));
    }

    private ProtocolError handleBind(Connection conn, byte[] cmdData) {
        return ProtocolError.UNSUPPORTED_COMMAND;
    }

    private ProtocolError handleUDPAssociate(Connection conn) {
        return ProtocolError.fromByte(udpHandler.handle(conn));
    }
}
