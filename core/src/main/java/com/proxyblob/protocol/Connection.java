package com.proxyblob.protocol;

import lombok.Getter;
import lombok.Setter;

import java.net.Socket;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.proxyblob.protocol.ProtocolError.ErrConnectionClosed;
import static com.proxyblob.protocol.ProtocolError.ErrNone;

@Getter
@Setter
public class Connection {

    // ConnectionState enum (matching Go iota)
    public static final int StateNew = 0;
    public static final int StateConnected = 1;
    public static final int StateClosed = 2;

    // ID uniquely identifies the connection
    private final UUID id;

    // State indicates current connection lifecycle phase
    private volatile int state;

    // Conn holds the network connection (optional)
    private Socket socket;

    // ReadBuffer receives data from the remote endpoint
    private final BlockingQueue<byte[]> readBuffer;

    // Closed signals connection termination
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // CreatedAt records connection creation time
    private final Instant createdAt;

    // LastActivity tracks most recent data transfer
    private volatile Instant lastActivity;

    // SecretKey holds encryption key for secure communication
    private byte[] secretKey;

    public Connection(UUID id) {
        this.id = id;
        this.state = StateNew;
        this.readBuffer = new LinkedBlockingQueue<>();
        this.createdAt = Instant.now();
        this.lastActivity = Instant.now();
    }

    public byte close() {
        if (state == StateClosed) {
            return ErrNone;
        }

        state = StateClosed;

        if (closed.compareAndSet(false, true)) {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (Exception e) {
                    return ErrConnectionClosed;
                }
            }
        }

        return ErrNone;
    }
}
