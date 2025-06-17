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

        System.out.println("[Connection] Created new connection: " + id);
    }

    public byte close() {
        if (state == StateClosed) {
            System.out.println("[Connection] Already closed: " + id);
            return ErrNone;
        }

        state = StateClosed;

        if (closed.compareAndSet(false, true)) {
            synchronized (closeSignal) {
                closeSignal.notifyAll();
                System.out.println("[Connection] closeSignal notified for: " + id);
            }

            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                    System.out.println("[Connection] TCP socket closed for: " + id);
                } catch (Exception e) {
                    System.out.println("[Connection] Failed to close TCP socket for: " + id);
                    return ErrConnectionClosed;
                }
            }

            if (datagramSocket != null && !datagramSocket.isClosed()) {
                try {
                    datagramSocket.close();
                    System.out.println("[Connection] UDP socket closed for: " + id);
                } catch (Exception e) {
                    System.out.println("[Connection] Failed to close UDP socket for: " + id);
                }
            }
        } else {
            System.out.println("[Connection] Already marked as closed: " + id);
        }

        return ErrNone;
    }

    public void awaitClose() throws InterruptedException {
        synchronized (closeSignal) {
            while (!closed.get()) {
                System.out.println("[Connection] Waiting for close: " + id);
                closeSignal.wait();
            }
        }
        System.out.println("[Connection] Closed signal received: " + id);
    }
}
