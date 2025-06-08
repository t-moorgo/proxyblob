package com.proxyblob.proxy.socks;

import com.proxyblob.protocol.error.ProtocolError;
import com.proxyblob.protocol.handler.BaseHandler;
import com.proxyblob.protocol.model.Connection;
import com.proxyblob.protocol.model.ConnectionState;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiredArgsConstructor
public class SocksConnectHandler {

    private final BaseHandler baseHandler;

    public byte handle(Connection conn, byte[] cmdData) {
        if (cmdData.length < 4) {
            sendMalformedResponse(conn);
            return ProtocolError.ADDRESS_NOT_SUPPORTED.getCode();
        }

        SocksAddressParser.Result parseResult = SocksAddressParser.parse(cmdData, 3);
        if (parseResult.errorCode() != ProtocolError.NONE.getCode()) {
            baseHandler.sendClose(conn.getId(), ProtocolError.fromByte(parseResult.errorCode()));
            return parseResult.errorCode();
        }

        String target = parseResult.hostAndPort();

        Socket targetConn = new Socket();
        try {
            targetConn.connect(new InetSocketAddress(
                    target.split(":")[0],
                    Integer.parseInt(target.split(":")[1])
            ), 10_000);
        } catch (IOException e) {
            byte errCode = mapSocketError(e);
            baseHandler.sendClose(conn.getId(), ProtocolError.fromByte(errCode));
            return errCode;
        }

        byte[] response = createSuccessResponse(targetConn);
        ProtocolError sendCode = baseHandler.sendData(conn.getId(), response);
        if (sendCode != ProtocolError.NONE) {
            try {
                targetConn.close();
            } catch (IOException ignored) {
            }
            return ProtocolError.PACKET_SEND_FAILED.getCode();
        }

        conn.setSocket(targetConn);
        conn.setState(ConnectionState.CONNECTED);

        return handleTCPDataTransfer(conn, targetConn);
    }

    private byte[] createSuccessResponse(Socket socket) {
        byte[] response = new byte[10];
        response[0] = 0x05; // Version
        response[1] = 0x00; // Success
        response[2] = 0x00; // Reserved
        response[3] = 0x01; // IPv4

        InetAddress localAddress = socket.getLocalAddress();
        int localPort = socket.getLocalPort();

        byte[] ipBytes = localAddress.getAddress();
        System.arraycopy(ipBytes, 0, response, 4, 4);
        response[8] = (byte) (localPort >> 8);
        response[9] = (byte) (localPort & 0xFF);

        return response;
    }

    private void sendMalformedResponse(Connection conn) {
        byte[] response = new byte[]{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
        baseHandler.sendData(conn.getId(), response);
    }

    private byte handleTCPDataTransfer(Connection conn, Socket targetConn) {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicBoolean closed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(2);

        executor.submit(() -> {
            try (InputStream in = targetConn.getInputStream()) {
                byte[] buf = new byte[128 * 1024];
                int len;
                while (!closed.get() && (len = in.read(buf)) != -1) {
                    byte[] data = Arrays.copyOf(buf, len);
                    ProtocolError err = baseHandler.sendData(conn.getId(), data);
                    if (err != ProtocolError.NONE) break;
                }
            } catch (IOException ignored) {
            } finally {
                closeConnection(conn, targetConn, closed);
                latch.countDown();
            }
        });

        executor.submit(() -> {
            try (OutputStream out = targetConn.getOutputStream()) {
                while (!closed.get()) {
                    byte[] data = conn.getReadBuffer().poll(500, TimeUnit.MILLISECONDS);
                    if (data != null) {
                        out.write(data);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                closeConnection(conn, targetConn, closed);
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        return ProtocolError.NONE.getCode();
    }

    private void closeConnection(Connection conn, Socket targetConn, AtomicBoolean closed) {
        if (closed.compareAndSet(false, true)) {
            conn.setState(ConnectionState.CLOSED);
            try {
                targetConn.close();
            } catch (IOException ignored) {
            }
        }
    }

    private byte mapSocketError(IOException e) {
        if (e instanceof SocketTimeoutException) {
            return ProtocolError.TTL_EXPIRED.getCode();
        } else if (e instanceof UnknownHostException) {
            return ProtocolError.HOST_UNREACHABLE.getCode();
        } else if (e instanceof ConnectException) {
            return ProtocolError.NETWORK_UNREACHABLE.getCode();
        }
        return ProtocolError.CONNECTION_REFUSED.getCode();
    }
}
