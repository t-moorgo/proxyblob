package com.proxyblob.proxy.server;

import com.proxyblob.protocol.error.ProtocolError;
import com.proxyblob.protocol.handler.BaseHandler;
import com.proxyblob.protocol.model.Connection;
import com.proxyblob.protocol.model.ConnectionState;
import com.proxyblob.proxy.PacketHandler;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RequiredArgsConstructor
public class ProxyServer implements PacketHandler {

    private static final Logger log = LoggerFactory.getLogger(ProxyServer.class);

    private final BaseHandler baseHandler;
    private ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private volatile boolean running = false;

    @Override
    public void start(String address) {
        String[] parts = address.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        try {
            serverSocket = new ServerSocket(port);
            running = true;
            executor.submit(this::acceptLoop);
            executor.submit(baseHandler::receiveLoop);
        } catch (IOException e) {
            log.error("Failed to start server on {}", address, e);
            stop();
        }
    }

    @Override
    public void stop() {
        running = false;
        baseHandler.stop();
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.warn("Error closing server socket", e);
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                executor.submit(() -> handleConnection(clientSocket));
            } catch (IOException e) {
                if (running) {
                    log.warn("Accept error", e);
                }
            }
        }
    }

    private void handleConnection(Socket clientSocket) {
        UUID connId = UUID.randomUUID();
        Connection connection = new Connection(connId);
        connection.setSocket(clientSocket);
        baseHandler.addConnection(connection);

        ProtocolError err = baseHandler.sendNewConnection(connId);
        if (err != ProtocolError.NONE) {
            baseHandler.removeConnection(connId);
            closeQuietly(clientSocket);
            return;
        }

        try {
            // Wait for ACK (agent side) with timeout
            byte[] ack = connection.getReadBuffer().poll(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (ack == null) {
                baseHandler.sendClose(connId, ProtocolError.TRANSPORT_TIMEOUT);
                baseHandler.removeConnection(connId);
                closeQuietly(clientSocket);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            baseHandler.sendClose(connId, ProtocolError.HANDLER_STOPPED);
            baseHandler.removeConnection(connId);
            closeQuietly(clientSocket);
            return;
        }

        connection.setState(ConnectionState.CONNECTED);
        connection.updateLastActivity();

        executor.submit(() -> forwardToAgent(clientSocket, connection));
        executor.submit(() -> forwardToClient(clientSocket, connection));
    }

    private void forwardToAgent(Socket clientSocket, Connection connection) {
        byte[] buffer = new byte[64 * 1024];
        try {
            while (!connection.isClosed() && running) {
                int read = clientSocket.getInputStream().read(buffer);
                if (read == -1) {
                    break;
                }

                ProtocolError err = baseHandler.sendData(connection.getId(), copyBytes(buffer, read));
                if (err != ProtocolError.NONE) {
                    log.error("Send to agent failed: {}", err);
                    break;
                }
                connection.updateLastActivity();
            }
        } catch (IOException e) {
            log.warn("Read from client failed: {}", e.getMessage());
        } finally {
            cleanupConnection(connection);
        }
    }

    private void forwardToClient(Socket clientSocket, Connection connection) {
        try {
            while (!connection.isClosed() && running) {
                byte[] data = connection.getReadBuffer().take();
                clientSocket.getOutputStream().write(data);
                connection.updateLastActivity();
            }
        } catch (IOException | InterruptedException e) {
            log.warn("Write to client failed: {}", e.getMessage());
        } finally {
            cleanupConnection(connection);
        }
    }

    private void cleanupConnection(Connection connection) {
        UUID connId = connection.getId();
        baseHandler.sendClose(connId, ProtocolError.CONNECTION_CLOSED);
        connection.close();
        baseHandler.removeConnection(connId);
        closeQuietly(connection.getSocket());
    }

    private void closeQuietly(Socket socket) {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignore) {
        }
    }

    private byte[] copyBytes(byte[] src, int length) {
        byte[] result = new byte[length];
        System.arraycopy(src, 0, result, 0, length);
        return result;
    }

    @Override
    public void receiveLoop() {
        baseHandler.receiveLoop();
    }

    @Override
    public ProtocolError onNew(UUID connectionId, byte[] data) {
        return ProtocolError.UNEXPECTED_PACKET;
    }

    @Override
    public ProtocolError onAck(UUID connectionId, byte[] data) {
        Connection conn = baseHandler.getConnection(connectionId);
        if (conn == null) return ProtocolError.CONNECTION_NOT_FOUND;
        if (conn.getState() != ConnectionState.NEW) return ProtocolError.INVALID_STATE;

        if (data.length < 32) return ProtocolError.INVALID_CRYPTO;

        byte[] clientPubKey = new byte[32];
        System.arraycopy(data, 0, clientPubKey, 0, 32);

        byte[] secret = conn.getSecretKey(); // [24 nonce][32 privKey]
        byte[] nonce = new byte[24];
        byte[] priv = new byte[32];
        System.arraycopy(secret, 0, nonce, 0, 24);
        System.arraycopy(secret, 24, priv, 0, 32);

        byte[] symmetricKey = com.proxyblob.protocol.crypto.KeyUtil.deriveSharedKey(
                new org.bouncycastle.crypto.params.X25519PrivateKeyParameters(priv, 0),
                new org.bouncycastle.crypto.params.X25519PublicKeyParameters(clientPubKey, 0),
                nonce
        );
        conn.setSecretKey(symmetricKey);
        conn.setState(ConnectionState.CONNECTED);
        conn.updateLastActivity();

        conn.getReadBuffer().offer(new byte[0]); // unblock waiting thread
        return ProtocolError.NONE;
    }

    @Override
    public ProtocolError onData(UUID connectionId, byte[] data) {
        Connection conn = baseHandler.getConnection(connectionId);
        if (conn == null) return ProtocolError.CONNECTION_NOT_FOUND;

        conn.updateLastActivity();

        try {
            byte[] decrypted = com.proxyblob.protocol.crypto.CipherUtil.decrypt(conn.getSecretKey(), data);
            conn.getReadBuffer().offer(decrypted);
            return ProtocolError.NONE;
        } catch (Exception e) {
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
        return ProtocolError.fromByte(errorCode);
    }
}
