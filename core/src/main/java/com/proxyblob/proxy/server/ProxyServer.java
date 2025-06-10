package com.proxyblob.proxy.server;

import com.proxyblob.protocol.BaseHandler;
import com.proxyblob.protocol.Connection;
import com.proxyblob.protocol.CryptoUtil;
import com.proxyblob.protocol.ProtocolError;
import com.proxyblob.proxy.PacketHandler;
import com.proxyblob.transport.Transport;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.proxyblob.protocol.Connection.StateConnected;
import static com.proxyblob.protocol.Connection.StateNew;

public class ProxyServer implements PacketHandler {

    private final BaseHandler baseHandler;
    private ServerSocket listener;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    // Конструктор
    public ProxyServer(Transport transport) {
        this.baseHandler = new BaseHandler(transport, this);
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

        new Thread(this::receiveLoop).start();
        new Thread(this::acceptLoop).start();
    }

    @Override
    public void stop() {
        baseHandler.closeAllConnections(); // Завершить все соединения
        baseHandler.stop();              // Отменить контекст
        if (listener != null && !listener.isClosed()) {
            try {
                listener.close(); // Закрыть сокет
            } catch (IOException e) {
                // Логгировать или проигнорировать
            }
        }
    }

    @Override
    public void receiveLoop() {
        baseHandler.receiveLoop();
    }

    @Override
    public byte onNew(UUID connectionId, byte[] data) {
        return ProtocolError.ErrUnexpectedPacket;
    }

    @Override
    public byte onAck(UUID connectionId, byte[] data) {
        Connection conn = baseHandler.getConnections().get(connectionId);
        if (conn == null) {
            return ProtocolError.ErrConnectionNotFound;
        }

        if (conn.getState() != StateNew) {
            return ProtocolError.ErrInvalidState;
        }

        if (data == null || data.length < 32) {
            return ProtocolError.ErrInvalidPacket;
        }

        byte[] clientPublicKey = Arrays.copyOfRange(data, 0, 32);

        byte[] serverData = conn.getSecretKey();
        if (serverData == null || serverData.length < 24 + 32) {
            return ProtocolError.ErrInvalidPacket;
        }

        byte[] nonce = Arrays.copyOfRange(serverData, 0, 24);
        byte[] serverPrivateKey = Arrays.copyOfRange(serverData, 24, 56);

        X25519PrivateKeyParameters privateKey = new X25519PrivateKeyParameters(serverPrivateKey, 0);
        X25519PublicKeyParameters publicKey = new X25519PublicKeyParameters(clientPublicKey, 0);
        byte[] sharedKey = CryptoUtil.deriveKey(privateKey, publicKey, nonce);
        if (sharedKey == null || sharedKey.length != CryptoUtil.KEY_SIZE) {
            return ProtocolError.ErrInvalidCrypto;
        }

        conn.setSecretKey(sharedKey);

        // Non-blocking signal for ACK received
        conn.getReadBuffer().offer(new byte[0]);
        conn.setState(StateConnected);
        conn.setLastActivity(Instant.now());

        return ProtocolError.ErrNone;
    }


    @Override
    public byte onData(UUID connectionId, byte[] data) {
        Connection conn = baseHandler.getConnections().get(connectionId);
        if (conn == null) {
            return ProtocolError.ErrConnectionNotFound;
        }

        conn.setLastActivity(Instant.now());

        CryptoUtil.CryptoResult result = CryptoUtil.decrypt(conn.getSecretKey(), data);
        if (result.status() != CryptoUtil.CryptoStatus.OK) {
            return ProtocolError.ErrInvalidCrypto;
        }

        // Writing to client is handled by forwardToClient thread
        try {
            if (baseHandler.getStopped().get()) {
                return ProtocolError.ErrConnectionClosed;
            }
            conn.getReadBuffer().put(result.data()); // blocking
            return ProtocolError.ErrNone;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProtocolError.ErrHandlerStopped;
        }
    }


    @Override
    public byte onClose(UUID connectionId, byte errorCode) {
        Connection conn = baseHandler.getConnections().get(connectionId);
        if (conn == null) {
            return ProtocolError.ErrNone; // Connection already removed
        }

        conn.close();
        baseHandler.getConnections().remove(connectionId);
        return errorCode;
    }

    public void acceptLoop() {
        while (!stopped.get()) {
            try {
                Socket clientSocket = listener.accept(); // блокирует

                if (stopped.get()) {
                    clientSocket.close();
                    return; // приложение остановлено
                }

                executor.submit(() -> handleConnection(clientSocket)); // аналог `go`
            } catch (IOException e) {
                if (stopped.get()) {
                    return; // тихо выходим при остановке
                }

                if (e instanceof SocketException) {
                    continue; // временная сетевая ошибка, повторить
                }

                // любые другие ошибки — выйти
                return;
            }
        }
    }

    private void handleConnection(Socket clientSocket) {
        try {
            UUID connId = UUID.randomUUID();
            Connection proxyConn = new Connection(connId);
            baseHandler.getConnections().put(connId, proxyConn);

            // 1. Инициализация соединения с агентом
            byte errCode = baseHandler.sendNewConnection(connId);
            if (errCode != ProtocolError.ErrNone) {
                baseHandler.getConnections().remove(connId);
                return;
            }

            // 2. Ожидание ACK от агента (5 сек timeout)
            boolean ackReceived = false;
            try {
                byte[] ack = proxyConn.getReadBuffer().poll(5, TimeUnit.SECONDS);
                if (ack != null) {
                    ackReceived = true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                baseHandler.sendClose(connId, ProtocolError.ErrHandlerStopped);
                baseHandler.getConnections().remove(connId);
                return;
            }

            if (!ackReceived) {
                baseHandler.sendClose(connId, ProtocolError.ErrTransportTimeout);
                baseHandler.getConnections().remove(connId);
                return;
            }

            // 3. Соединение установлено, запускаем bidirectional forwarding
            proxyConn.setState(StateConnected);
            proxyConn.setLastActivity(Instant.now());

            BlockingQueue<Byte> errQueue = new ArrayBlockingQueue<>(2);

            executor.submit(() -> forwardToAgent(clientSocket, proxyConn, errQueue));
            executor.submit(() -> forwardToClient(clientSocket, proxyConn, errQueue));

            while (true) {
                if (stopped.get()) break;
                if (proxyConn.getClosed().get()) break;

                Byte result = errQueue.poll(100, TimeUnit.MILLISECONDS);
                if (result != null) {
                    if (result != ProtocolError.ErrNone && result != ProtocolError.ErrConnectionClosed) {
                        //TODO НУЖНО ЧТО ТО ПРОПИСАТЬ
                    }
                    break;
                }
            }

            baseHandler.sendClose(connId, ProtocolError.ErrConnectionClosed);
            proxyConn.close();
            baseHandler.getConnections().remove(connId);
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignore) {
                //TODO НУЖНО ЧТО ТО ПРОПИСАТЬ
            }
        }
    }

    private void forwardToAgent(Socket clientSocket, Connection proxyConn, BlockingQueue<Byte> errQueue) {
        byte[] buffer = new byte[64 * 1024];

        while (true) {
            if (proxyConn.getClosed().get() || stopped.get()) {
                return;
            }

            int readBytes;
            try {
                readBytes = clientSocket.getInputStream().read(buffer);
                if (readBytes == -1) {
                    errQueue.offer(ProtocolError.ErrConnectionClosed);
                    return;
                }
            } catch (IOException e) {
                if (e instanceof java.net.SocketTimeoutException) {
                    errQueue.offer(ProtocolError.ErrTransportTimeout);
                } else {
                    errQueue.offer(ProtocolError.ErrNetworkUnreachable);
                }
                return;
            }

            byte resultCode = baseHandler.sendData(proxyConn.getId(), Arrays.copyOf(buffer, readBytes));
            if (resultCode != ProtocolError.ErrNone) {
                errQueue.offer(ProtocolError.ErrPacketSendFailed);
                return;
            }

            proxyConn.setLastActivity(Instant.now());
        }
    }

    private void forwardToClient(Socket clientSocket, Connection proxyConn, BlockingQueue<Byte> errQueue) {
        try {
            OutputStream outputStream = clientSocket.getOutputStream();

            while (true) {
                if (proxyConn.getClosed().get() || stopped.get()) return;

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

        } catch (IOException e) {
            errQueue.offer(ProtocolError.ErrConnectionClosed); // fallback
        }
    }

    private byte mapIOException(IOException e) {
        if (e instanceof SocketTimeoutException) {
            return ProtocolError.ErrTransportTimeout;
        } else if ("Connection reset".equals(e.getMessage()) || e instanceof EOFException) {
            return ProtocolError.ErrConnectionClosed;
        } else {
            return ProtocolError.ErrNetworkUnreachable;
        }
    }

}
