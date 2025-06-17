package com.proxyblob.proxy.server;

import com.proxyblob.context.AppContext;
import com.proxyblob.protocol.BaseHandler;
import com.proxyblob.protocol.Connection;
import com.proxyblob.protocol.CryptoUtil;
import com.proxyblob.protocol.dto.CryptoResult;
import com.proxyblob.proxy.PacketHandler;
import com.proxyblob.transport.Transport;
import lombok.Getter;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.proxyblob.errorcodes.ErrorCodes.ErrConnectionClosed;
import static com.proxyblob.errorcodes.ErrorCodes.ErrConnectionNotFound;
import static com.proxyblob.errorcodes.ErrorCodes.ErrHandlerStopped;
import static com.proxyblob.errorcodes.ErrorCodes.ErrInvalidPacket;
import static com.proxyblob.errorcodes.ErrorCodes.ErrInvalidState;
import static com.proxyblob.errorcodes.ErrorCodes.ErrNetworkUnreachable;
import static com.proxyblob.errorcodes.ErrorCodes.ErrNone;
import static com.proxyblob.errorcodes.ErrorCodes.ErrPacketSendFailed;
import static com.proxyblob.errorcodes.ErrorCodes.ErrTransportTimeout;
import static com.proxyblob.errorcodes.ErrorCodes.ErrUnexpectedPacket;
import static com.proxyblob.protocol.Connection.StateConnected;
import static com.proxyblob.protocol.Connection.StateNew;

@Getter
public class ProxyServer implements PacketHandler {

    private final BaseHandler baseHandler;
    private ServerSocket listener;
    private final AppContext context;

    public ProxyServer(Transport transport, AppContext context) {
        this.context = context;
        this.baseHandler = new BaseHandler(transport, this, context);
        System.out.println("[ProxyServer] Initialized with BaseHandler");
    }

    @Override
    public void start(String address) {
        try {
            String[] parts = address.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);

            this.listener = new ServerSocket();
            this.listener.bind(new InetSocketAddress(host, port));
            System.out.println("[ProxyServer] Listening on " + address);
        } catch (Exception e) {
            System.out.println("[ProxyServer] Failed to start: " + e.getMessage());
            stop();
            return;
        }

        context.getGeneralExecutor().submit(this::receiveLoop);
        context.getGeneralExecutor().submit(this::acceptLoop);
    }

    @Override
    public void stop() {
        baseHandler.closeAllConnections();
        context.stop();
        if (listener != null && !listener.isClosed()) {
            try {
                listener.close();
                System.out.println("[ProxyServer] Listener closed");
            } catch (IOException e) {
                System.out.println("[ProxyServer] Failed to close listener: " + e.getMessage());
            }
        }
    }

    @Override
    public void receiveLoop() {
        System.out.println("[ProxyServer] Starting receive loop");
        baseHandler.receiveLoop();
    }

    @Override
    public byte onNew(UUID connectionId, byte[] data) {
        System.out.println("[ProxyServer] onNew called: " + connectionId);

        Connection conn = baseHandler.getConnections().get(connectionId);
        if (conn == null) {
            return ErrConnectionNotFound;
        }

        conn.getReadBuffer().offer(data);
        return ErrNone;
    }

    @Override
    public byte onAck(UUID connectionId, byte[] data) {
        System.out.println("[ProxyServer] onAck called for connection: " + connectionId);

        Connection conn = baseHandler.getConnections().get(connectionId);
        if (conn == null) {
            System.out.println("[ProxyServer] Connection not found for ACK");
            return ErrConnectionNotFound;
        }

        if (conn.getState() != StateNew) {
            return ErrInvalidState;
        }

        if (data == null || data.length < 32) {
            return ErrInvalidPacket;
        }

        byte[] clientPublicKey = Arrays.copyOfRange(data, 0, 32);
        byte[] serverData = conn.getSecretKey();
        if (serverData == null || serverData.length != 56) {
            return ErrInvalidPacket;
        }

        byte[] nonce = Arrays.copyOfRange(serverData, 0, 24);
        byte[] serverPrivateKey = Arrays.copyOfRange(serverData, 24, 56);

        CryptoResult result = CryptoUtil.deriveKey(
                new X25519PrivateKeyParameters(serverPrivateKey, 0),
                new X25519PublicKeyParameters(clientPublicKey, 0),
                nonce
        );
        if (result.getData() == null || result.getData().length != CryptoUtil.KEY_SIZE) {
            return result.getStatus();
        }

        conn.setSecretKey(result.getData());
        conn.getReadBuffer().offer(new byte[0]);
        conn.setState(StateConnected);
        conn.setLastActivity(Instant.now());

        System.out.println("[ProxyServer] ACK complete, connection ready: " + connectionId);
        return ErrNone;
    }

    @Override
    public byte onData(UUID connectionId, byte[] data) {
        Connection conn = baseHandler.getConnections().get(connectionId);
        if (conn == null) {
            return ErrConnectionNotFound;
        }

        conn.setLastActivity(Instant.now());

        CryptoResult result = CryptoUtil.decrypt(conn.getSecretKey(), data);
        if (result.getStatus() != ErrNone) {
            return result.getStatus();
        }

        try {
            if (context.isStopped()) {
                return ErrConnectionClosed;
            }
            conn.getReadBuffer().put(result.getData());
            return ErrNone;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ErrHandlerStopped;
        }
    }

    @Override
    public byte onClose(UUID connectionId, byte errorCode) {
        Connection conn = baseHandler.getConnections().get(connectionId);
        if (conn != null) {
            conn.close();
            baseHandler.getConnections().remove(connectionId);
            System.out.println("[ProxyServer] Closed connection: " + connectionId + " with code " + errorCode);
        }
        return errorCode;
    }

    private void acceptLoop() {
        System.out.println("[ProxyServer] Starting accept loop");
        while (!context.isStopped()) {
            try {
                Socket clientSocket = listener.accept();
                System.out.println("[ProxyServer] Accepted new connection from: " + clientSocket.getRemoteSocketAddress());
                context.getGeneralExecutor().submit(() -> handleConnection(clientSocket));
            } catch (IOException e) {
                if (context.isStopped()) return;
                if (!(e instanceof SocketTimeoutException || e instanceof SocketException)) {
                    System.out.println("[ProxyServer] Error in acceptLoop: " + e.getMessage());
                    return;
                }
            }
        }
    }

    private void handleConnection(Socket clientSocket) {
        UUID connId = UUID.randomUUID();
        System.out.println("[ProxyServer] Handling new connection: " + connId);
        try {
            Connection proxyConn = new Connection(connId);
            baseHandler.getConnections().put(connId, proxyConn);

            byte errCode = baseHandler.sendNewConnection(connId);
            if (errCode != ErrNone) {
                baseHandler.getConnections().remove(connId);
                return;
            }

            byte[] ack = proxyConn.getReadBuffer().poll(5, TimeUnit.SECONDS);
            if (ack == null) {
                byte closeReason = context.isStopped() ? ErrHandlerStopped : ErrTransportTimeout;
                baseHandler.sendClose(connId, closeReason);
                baseHandler.getConnections().remove(connId);
                return;
            }

            proxyConn.setState(StateConnected);
            proxyConn.setLastActivity(Instant.now());

            BlockingQueue<Byte> errQueue = new ArrayBlockingQueue<>(2);
            context.getGeneralExecutor().submit(() -> forwardToAgent(clientSocket, proxyConn, errQueue));
            context.getGeneralExecutor().submit(() -> forwardToClient(clientSocket, proxyConn, errQueue));

            while (true) {
                if (context.isStopped() || proxyConn.getClosed().get()) break;
                Byte result = errQueue.poll(100, TimeUnit.MILLISECONDS);
                if (result != null && result != ErrNone && result != ErrConnectionClosed) {
                    System.out.println("[ProxyServer] Forwarding error: " + result);
                    break;
                }
            }

            baseHandler.sendClose(connId, ErrConnectionClosed);
            proxyConn.close();
            baseHandler.getConnections().remove(connId);
            System.out.println("[ProxyServer] Connection fully closed: " + connId);
        } catch (Exception e) {
            System.out.println("[ProxyServer] handleConnection error: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignore) {
            }
        }
    }

    private void forwardToAgent(Socket clientSocket, Connection proxyConn, BlockingQueue<Byte> errQueue) {
        byte[] buffer = new byte[64 * 1024];
        while (!proxyConn.getClosed().get() && !context.isStopped()) {
            try {
                int readBytes = clientSocket.getInputStream().read(buffer);
                if (readBytes == -1) {
                    errQueue.offer(ErrConnectionClosed);
                    return;
                }

                byte[] dataToSend = Arrays.copyOf(buffer, readBytes);
                byte resultCode = baseHandler.sendData(proxyConn.getId(), dataToSend);
                if (resultCode != ErrNone) {
                    errQueue.offer(ErrPacketSendFailed);
                    return;
                }

                proxyConn.setLastActivity(Instant.now());
            } catch (IOException e) {
                errQueue.offer(mapIOException(e));
                return;
            }
        }
    }

    private void forwardToClient(Socket clientSocket, Connection proxyConn, BlockingQueue<Byte> errQueue) {
        while (!proxyConn.getClosed().get() && !context.isStopped()) {
            try {
                byte[] data = proxyConn.getReadBuffer().take();
                proxyConn.setLastActivity(Instant.now());
                clientSocket.getOutputStream().write(data);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                errQueue.offer(mapIOException(e));
                return;
            }
        }
    }

    private byte mapIOException(IOException e) {
        if (e instanceof SocketTimeoutException) return ErrTransportTimeout;
        if (e instanceof EOFException || e instanceof SocketException) return ErrConnectionClosed;
        return ErrNetworkUnreachable;
    }
}
