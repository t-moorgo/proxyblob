package com.proxyblob.proxy.socks;

import com.proxyblob.protocol.BaseHandler;
import com.proxyblob.protocol.Connection;
import com.proxyblob.protocol.ProtocolError;
import com.proxyblob.proxy.socks.dto.ParsedAddress;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.proxyblob.protocol.Connection.StateConnected;
import static com.proxyblob.protocol.ProtocolError.ErrAddressNotSupported;
import static com.proxyblob.protocol.ProtocolError.ErrConnectionRefused;
import static com.proxyblob.protocol.ProtocolError.ErrHostUnreachable;
import static com.proxyblob.protocol.ProtocolError.ErrNetworkUnreachable;
import static com.proxyblob.protocol.ProtocolError.ErrNone;
import static com.proxyblob.protocol.ProtocolError.ErrPacketSendFailed;
import static com.proxyblob.protocol.ProtocolError.ErrTTLExpired;
import static com.proxyblob.proxy.socks.SocksConstants.GeneralFailure;
import static com.proxyblob.proxy.socks.SocksConstants.IPv4;
import static com.proxyblob.proxy.socks.SocksConstants.Succeeded;
import static com.proxyblob.proxy.socks.SocksConstants.Version5;

@RequiredArgsConstructor
public class SocksConnectHandler {

    private final BaseHandler baseHandler;

    public byte handle(Connection conn, byte[] cmdData) {
        if (cmdData == null || cmdData.length < 4) {
            byte[] response = new byte[]{
                    Version5, GeneralFailure, 0x00, IPv4, 0, 0, 0, 0, 0, 0
            };
            baseHandler.sendData(conn.getId(), response);
            return ErrAddressNotSupported;
        }

        // Parse target
        ParsedAddress parsedAddress = SocksAddressParser.parseAddress(
                Arrays.copyOfRange(cmdData, 3, cmdData.length)
        );
        if (parsedAddress.getErrorCode() != ErrNone) {
            SocksErrorUtil.sendError(baseHandler, conn, parsedAddress.getErrorCode());
            return parsedAddress.getErrorCode();
        }

        Socket targetSocket;
        byte errCode;
        try {
            String[] parts = parsedAddress.getHostAndPort().split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);

            SocketAddress sockaddr = new InetSocketAddress(host, port);
            targetSocket = new Socket();
            targetSocket.connect(sockaddr, 10_000); // 10 sec timeout

        } catch (SocketTimeoutException e) {
            errCode = ErrTTLExpired;
            SocksErrorUtil.sendError(baseHandler, conn, errCode);
            return errCode;
        } catch (ConnectException e) {
            errCode = ErrConnectionRefused;
            SocksErrorUtil.sendError(baseHandler, conn, errCode);
            return errCode;
        } catch (UnknownHostException | NoRouteToHostException e) {
            errCode = ErrHostUnreachable;
            SocksErrorUtil.sendError(baseHandler, conn, errCode);
            return errCode;
        } catch (IOException e) {
            errCode = ErrNetworkUnreachable;
            SocksErrorUtil.sendError(baseHandler, conn, errCode);
            return errCode;
        }

        // Send success response
        InetSocketAddress local = (InetSocketAddress) targetSocket.getLocalSocketAddress();
        byte[] ipBytes = local.getAddress().getAddress();
        int port = local.getPort();

        byte[] response = new byte[10];
        response[0] = Version5;
        response[1] = Succeeded;
        response[2] = 0x00;
        response[3] = IPv4;
        System.arraycopy(ipBytes, 0, response, 4, 4);
        ByteBuffer.wrap(response, 8, 2).putShort((short) port);

        errCode = baseHandler.sendData(conn.getId(), response);
        if (errCode != ErrNone) {
            try {
                targetSocket.close();
            } catch (IOException ignored) {
            }
            return ErrPacketSendFailed;
        }

        conn.setSocket(targetSocket);
        conn.setState(StateConnected);

        return new SocksConnectHandler(baseHandler).handleTCPDataTransfer(conn, targetSocket);
    }

    private byte handleTCPDataTransfer(Connection conn, Socket tcpConn) {
        BlockingQueue<byte[]> clientToTarget = new LinkedBlockingQueue<>();
        BlockingQueue<byte[]> targetToClient = new LinkedBlockingQueue<>();
        BlockingQueue<Byte> errorQueue = new ArrayBlockingQueue<>(2);

        // From SOCKS client → target
        baseHandler.getContext().getGeneralExecutor().submit(() -> {
            try {
                while (true) {
                    if (conn.getClosed().get() || baseHandler.getContext().isStopped()) return;

                    byte[] data = conn.getReadBuffer().poll(100, TimeUnit.MILLISECONDS);
                    if (data != null) {
                        clientToTarget.put(data);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // From target → SOCKS client
        baseHandler.getContext().getGeneralExecutor().submit(() -> {
            try (InputStream in = tcpConn.getInputStream()) {
                byte[] buffer = new byte[128 * 1024];
                while (true) {
                    int read = in.read(buffer);
                    if (read == -1) {
                        errorQueue.put(ProtocolError.ErrConnectionClosed);
                        break;
                    }

                    byte[] data = Arrays.copyOf(buffer, read);
                    targetToClient.put(data);
                }
            } catch (SocketTimeoutException e) {
                try {
                    errorQueue.put(ErrTTLExpired);
                } catch (InterruptedException ignored) {}
            } catch (IOException | InterruptedException e) {
                try {
                    errorQueue.put(ErrHostUnreachable);
                } catch (InterruptedException ignored) {}
            }
        });

        // Main loop
        try {
            while (true) {
                if (conn.getClosed().get()) {
                    tcpConn.close();
                    return ErrNone;
                }

                if (baseHandler.getContext().isStopped()) {
                    tcpConn.close();
                    return ProtocolError.ErrHandlerStopped;
                }

                Byte err = errorQueue.poll(100, TimeUnit.MILLISECONDS);
                if (err != null) {
                    tcpConn.close();
                    return err;
                }

                byte[] toTarget = clientToTarget.poll(10, TimeUnit.MILLISECONDS);
                if (toTarget != null) {
                    try {
                        tcpConn.getOutputStream().write(toTarget);
                    } catch (SocketTimeoutException e) {
                        tcpConn.close();
                        return ErrTTLExpired;
                    } catch (IOException e) {
                        tcpConn.close();
                        return ErrHostUnreachable;
                    }
                }

                byte[] toClient = targetToClient.poll(10, TimeUnit.MILLISECONDS);
                if (toClient != null) {
                    byte errCode = baseHandler.sendData(conn.getId(), toClient);
                    if (errCode != ErrNone) {
                        tcpConn.close();
                        return ErrPacketSendFailed;
                    }
                }
            }
        } catch (InterruptedException | IOException e) {
            Thread.currentThread().interrupt();
            try {
                tcpConn.close();
            } catch (IOException ignored) {}
            return ProtocolError.ErrHandlerStopped;
        }
    }

}
