package com.proxyblob.protocol;

import lombok.Getter;
import lombok.Setter;

import java.net.DatagramSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.proxyblob.errorcodes.ErrorCodes.ErrConnectionClosed;
import static com.proxyblob.errorcodes.ErrorCodes.ErrNone;

@Getter
@Setter
public class Connection {

    public static final int StateNew = 0;
    public static final int StateConnected = 1;
    public static final int StateClosed = 2;

    private final UUID id;
    private final BlockingQueue<byte[]> readBuffer;
    private final Instant createdAt;

    private volatile Instant lastActivity;
    private volatile int state;

    private Socket socket;
    private DatagramSocket datagramSocket;
    private byte[] secretKey;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object closeSignal = new Object();

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
            synchronized (closeSignal) {
                closeSignal.notifyAll();
            }

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

    public void awaitClose() throws InterruptedException {
        synchronized (closeSignal) {
            while (!closed.get()) {
                closeSignal.wait();
            }
        }
    }
}
