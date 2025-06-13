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
    }

    @Override
    public void start(String address) {
        try {
            String[] parts = address.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);

            this.listener = new ServerSocket();
            this.listener.bind(new InetSocketAddress(host, port));
        } catch (IOException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
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
            } catch (IOException e) {
                //TODO что то сделать
            }
        }
    }

    @Override
    public void receiveLoop() {
        baseHandler.receiveLoop();
    }

    @Override
    public byte onNew(UUID connectionId, byte[] data) {
        return ErrUnexpectedPacket;
    }

    @Override
    public byte onAck(UUID connectionId, byte[] data) {
        Connection conn = baseHandler.getConnections().get(connectionId);
        if (conn == null) {
            return ErrConnectionNotFound;
        }

        if (conn.getState() != StateNew) {
            return ErrInvalidState;
        }

        if (data == null) {
            return ErrInvalidPacket;
        }
        if (data.length < 32) {
            return ErrInvalidPacket;
        }

        byte[] clientPublicKey = Arrays.copyOfRange(data, 0, 32);

        byte[] serverData = conn.getSecretKey();
        if (serverData == null) {
            return ErrInvalidPacket;
        }
        if (serverData.length != 56) {
            return ErrInvalidPacket;
        }

        byte[] nonce = Arrays.copyOfRange(serverData, 0, 24);
        byte[] serverPrivateKey = Arrays.copyOfRange(serverData, 24, 56);

        X25519PrivateKeyParameters privateKey = new X25519PrivateKeyParameters(serverPrivateKey, 0);
        X25519PublicKeyParameters publicKey = new X25519PublicKeyParameters(clientPublicKey, 0);
        CryptoResult result = CryptoUtil.deriveKey(privateKey, publicKey, nonce);
        if (result.getData() == null || result.getData().length != CryptoUtil.KEY_SIZE) {
            return result.getStatus();
        }

        conn.setSecretKey(result.getData());
        conn.getReadBuffer().offer(new byte[0]);
        conn.setState(StateConnected);
        conn.setLastActivity(Instant.now());

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
        if (conn == null) {
            return ErrNone;
        }

        conn.close();
        baseHandler.getConnections().remove(connectionId);
        return errorCode;
    }

    private void acceptLoop() {
        while (!context.isStopped()) {
            try {
                Socket clientSocket = listener.accept();
                context.getGeneralExecutor().submit(() -> handleConnection(clientSocket)); // аналог `go`
            } catch (IOException e) {
                if (context.isStopped()) {
                    return;
                }

                if (e instanceof SocketTimeoutException || e instanceof SocketException) {
                    continue;
                }

                return;
            }
        }
    }

    private void handleConnection(Socket clientSocket) {
        try {
            UUID connId = UUID.randomUUID();
            Connection proxyConn = new Connection(connId);
            baseHandler.getConnections().put(connId, proxyConn);

            byte errCode = baseHandler.sendNewConnection(connId);
            if (errCode != ErrNone) {
                baseHandler.getConnections().remove(connId);
                return;
            }

            byte[] ack;
            try {
                ack = proxyConn.getReadBuffer().poll(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                baseHandler.sendClose(connId, ErrHandlerStopped);
                baseHandler.getConnections().remove(connId);
                return;
            }

            if (ack == null) {
                if (context.isStopped()) {
                    baseHandler.sendClose(connId, ErrHandlerStopped);
                } else {
                    baseHandler.sendClose(connId, ErrTransportTimeout);
                }
                baseHandler.getConnections().remove(connId);
                return;
            }

            proxyConn.setState(StateConnected);
            proxyConn.setLastActivity(Instant.now());

            BlockingQueue<Byte> errQueue = new ArrayBlockingQueue<>(2);

            context.getGeneralExecutor().submit(() -> forwardToAgent(clientSocket, proxyConn, errQueue));
            context.getGeneralExecutor().submit(() -> forwardToClient(clientSocket, proxyConn, errQueue));

            while (true) {
                if (context.isStopped()) break;
                if (proxyConn.getClosed().get()) break;

                try {
                    Byte result = errQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (result != null) {
                        if (result != ErrNone && result != ErrConnectionClosed) {
                            //TODO что то сделать
                        }
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }


            baseHandler.sendClose(connId, ErrConnectionClosed);
            proxyConn.close();
            baseHandler.getConnections().remove(connId);
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignore) {
                //TODO что то сделать
            }
        }
    }

    private void forwardToAgent(Socket clientSocket, Connection proxyConn, BlockingQueue<Byte> errQueue) {
        byte[] buffer = new byte[64 * 1024];

        while (true) {
            if (proxyConn.getClosed().get() || context.isStopped()) {
                return;
            }

            int readBytes;
            try {
                readBytes = clientSocket.getInputStream().read(buffer);
                if (readBytes == -1) {
                    errQueue.offer(ErrConnectionClosed);
                    return;
                }
            } catch (IOException e) {
                if (e instanceof java.net.SocketTimeoutException) {
                    errQueue.offer(ErrTransportTimeout);
                } else {
                    errQueue.offer(ErrNetworkUnreachable);
                }
                return;
            }

            byte resultCode = baseHandler.sendData(proxyConn.getId(), Arrays.copyOf(buffer, readBytes));
            if (resultCode != ErrNone) {
                errQueue.offer(ErrPacketSendFailed);
                return;
            }

            proxyConn.setLastActivity(Instant.now());
        }
    }

    private void forwardToClient(Socket clientSocket, Connection proxyConn, BlockingQueue<Byte> errQueue) {
        while (true) {
            if (proxyConn.getClosed().get() || context.isStopped()) return;

            byte[] data;
            try {
                data = proxyConn.getReadBuffer().take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            proxyConn.setLastActivity(Instant.now());

            try {
                clientSocket.getOutputStream().write(data);
            } catch (IOException e) {
                errQueue.offer(mapIOException(e));
                return;
            }
        }
    }

    private byte mapIOException(IOException e) {
        if (e instanceof SocketTimeoutException) {
            return ErrTransportTimeout;
        } else if (e instanceof EOFException || e instanceof SocketException) {
            return ErrConnectionClosed;
        } else {
            return ErrNetworkUnreachable;
        }
    }
}
