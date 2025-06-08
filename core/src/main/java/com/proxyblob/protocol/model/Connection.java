package com.proxyblob.protocol.model;

import com.proxyblob.protocol.error.ProtocolError;
import lombok.Getter;
import lombok.Setter;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Getter
@Setter
public class Connection {

    private final UUID id;
    private volatile ConnectionState state;

    private Socket socket;
    private DatagramSocket datagramSocket;
    private InetSocketAddress clientDatagramAddress;

    private final BlockingQueue<byte[]> readBuffer = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final Instant createdAt;
    private volatile Instant lastActivity;

    private byte[] secretKey;

    public Connection(UUID id) {
        this.id = id;
        this.state = ConnectionState.NEW;
        this.createdAt = Instant.now();
        this.lastActivity = this.createdAt;
    }

    public void updateLastActivity() {
        this.lastActivity = Instant.now();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public ProtocolError close() {
        if (closed.compareAndSet(false, true)) {
            this.state = ConnectionState.CLOSED;
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
                if (datagramSocket != null && !datagramSocket.isClosed()) {
                    datagramSocket.close();
                }
            } catch (Exception e) {
                return ProtocolError.CONNECTION_CLOSED;
            }
        }
        return ProtocolError.NONE;
    }
}
