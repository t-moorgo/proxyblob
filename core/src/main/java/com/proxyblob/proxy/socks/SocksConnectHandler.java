package com.proxyblob.proxy.socks;

import com.proxyblob.protocol.BaseHandler;
import com.proxyblob.protocol.Connection;
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

import static com.proxyblob.errorcodes.ErrorCodes.ErrAddressNotSupported;
import static com.proxyblob.errorcodes.ErrorCodes.ErrConnectionClosed;
import static com.proxyblob.errorcodes.ErrorCodes.ErrConnectionRefused;
import static com.proxyblob.errorcodes.ErrorCodes.ErrHandlerStopped;
import static com.proxyblob.errorcodes.ErrorCodes.ErrHostUnreachable;
import static com.proxyblob.errorcodes.ErrorCodes.ErrNetworkUnreachable;
import static com.proxyblob.errorcodes.ErrorCodes.ErrNone;
import static com.proxyblob.errorcodes.ErrorCodes.ErrPacketSendFailed;
import static com.proxyblob.errorcodes.ErrorCodes.ErrTTLExpired;
import static com.proxyblob.protocol.Connection.StateConnected;
import static com.proxyblob.proxy.socks.SocksConstants.GeneralFailure;
import static com.proxyblob.proxy.socks.SocksConstants.IPv4;
import static com.proxyblob.proxy.socks.SocksConstants.Succeeded;
import static com.proxyblob.proxy.socks.SocksConstants.Version5;

@RequiredArgsConstructor
public class SocksConnectHandler {

    private final BaseHandler baseHandler;

    public byte handle(Connection conn, byte[] cmdData) {
        System.out.println("[SocksConnectHandler] Handling CONNECT command...");

        if (cmdData == null || cmdData.length < 4) {
            System.out.println("[SocksConnectHandler] Invalid command data length: " + (cmdData == null ? "null" : cmdData.length));
            byte[] response = new byte[]{
                    Version5, GeneralFailure, 0x00, IPv4, 0, 0, 0, 0, 0, 0
            };
            baseHandler.sendData(conn.getId(), response);
            return ErrAddressNotSupported;
        }

        ParsedAddress parsedAddress = SocksAddressParser.parseAddress(Arrays.copyOfRange(cmdData, 3, cmdData.length));
        if (parsedAddress.getErrorCode() != ErrNone) {
            System.out.println("[SocksConnectHandler] Address parsing failed: " + parsedAddress.getErrorCode());
            SocksErrorUtil.sendError(baseHandler, conn, parsedAddress.getErrorCode());
            return parsedAddress.getErrorCode();
        }

        System.out.println("[SocksConnectHandler] Target: " + parsedAddress.getHostAndPort());

        Socket targetSocket;
        byte errCode;
        try {
            String[] parts = parsedAddress.getHostAndPort().split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);

            SocketAddress sockaddr = InetSocketAddress.createUnresolved(host, port);
            targetSocket = new Socket();
            targetSocket.connect(sockaddr, 10_000);
            System.out.println("[SocksConnectHandler] Connected to target: " + host + ":" + port);

        } catch (SocketTimeoutException e) {
            System.out.println("[SocksConnectHandler] Connection timed out");
            errCode = ErrTTLExpired;
            SocksErrorUtil.sendError(baseHandler, conn, errCode);
            return errCode;
        } catch (ConnectException e) {
            System.out.println("[SocksConnectHandler] Connection refused");
            errCode = ErrConnectionRefused;
            SocksErrorUtil.sendError(baseHandler, conn, errCode);
            return errCode;
        } catch (UnknownHostException | NoRouteToHostException e) {
            System.out.println("[SocksConnectHandler] Host unreachable: " + e.getMessage());
            errCode = ErrHostUnreachable;
            SocksErrorUtil.sendError(baseHandler, conn, errCode);
            return errCode;
        } catch (IOException e) {
            System.out.println("[SocksConnectHandler] Network error: " + e.getMessage());
            errCode = ErrNetworkUnreachable;
            SocksErrorUtil.sendError(baseHandler, conn, errCode);
            return errCode;
        }

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
            System.out.println("[SocksConnectHandler] Failed to send success reply to client, closing target socket");
            try {
                targetSocket.close();
            } catch (IOException ignored) {
            }
            return ErrPacketSendFailed;
        }

        conn.setSocket(targetSocket);
        conn.setState(StateConnected);
        baseHandler.getConnections().put(conn.getId(), conn);
        System.out.println("[SocksConnectHandler] Proxy ready, starting TCP data transfer");

        return new SocksConnectHandler(baseHandler).handleTCPDataTransfer(conn, targetSocket);
    }

    private byte handleTCPDataTransfer(Connection conn, Socket tcpConn) {
        BlockingQueue<byte[]> clientToTarget = new LinkedBlockingQueue<>();
        BlockingQueue<byte[]> targetToClient = new LinkedBlockingQueue<>();
        BlockingQueue<Byte> errorQueue = new ArrayBlockingQueue<>(2);

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

        baseHandler.getContext().getGeneralExecutor().submit(() -> {
            try (InputStream in = tcpConn.getInputStream()) {
                byte[] buffer = new byte[128 * 1024];
                while (true) {
                    int read = in.read(buffer);
                    if (read == -1) {
                        errorQueue.put(ErrConnectionClosed);
                        System.out.println("[SocksConnectHandler] Target closed connection");
                        break;
                    }

                    byte[] data = Arrays.copyOf(buffer, read);
                    targetToClient.put(data);
                }
            } catch (SocketTimeoutException e) {
                System.out.println("[SocksConnectHandler] Read timeout from target");
                try {
                    errorQueue.put(ErrTTLExpired);
                } catch (InterruptedException ignored) {
                }
            } catch (IOException | InterruptedException e) {
                System.out.println("[SocksConnectHandler] Read error: " + e.getMessage());
                try {
                    errorQueue.put(ErrHostUnreachable);
                } catch (InterruptedException ignored) {
                }
            }
        });

        try {
            while (true) {
                if (conn.getClosed().get()) {
                    tcpConn.close();
                    System.out.println("[SocksConnectHandler] Connection closed");
                    return ErrNone;
                }

                if (baseHandler.getContext().isStopped()) {
                    tcpConn.close();
                    System.out.println("[SocksConnectHandler] Context stopped");
                    return ErrHandlerStopped;
                }

                Byte err = errorQueue.poll(100, TimeUnit.MILLISECONDS);
                if (err != null) {
                    System.out.println("[SocksConnectHandler] Error in target stream: " + err);
                    tcpConn.close();
                    return err;
                }

                byte[] toTarget = clientToTarget.poll(10, TimeUnit.MILLISECONDS);
                if (toTarget != null) {
                    try {
                        tcpConn.getOutputStream().write(toTarget);
                    } catch (SocketTimeoutException e) {
                        System.out.println("[SocksConnectHandler] Write timeout to target");
                        tcpConn.close();
                        return ErrTTLExpired;
                    } catch (IOException e) {
                        System.out.println("[SocksConnectHandler] Write error to target: " + e.getMessage());
                        tcpConn.close();
                        return ErrHostUnreachable;
                    }
                }

                byte[] toClient = targetToClient.poll(10, TimeUnit.MILLISECONDS);
                if (toClient != null) {
                    byte errCode = baseHandler.sendData(conn.getId(), toClient);
                    if (errCode != ErrNone) {
                        System.out.println("[SocksConnectHandler] Failed to send to client, errCode=" + errCode);
                        tcpConn.close();
                        return ErrPacketSendFailed;
                    }
                }
            }
        } catch (InterruptedException | IOException e) {
            Thread.currentThread().interrupt();
            System.out.println("[SocksConnectHandler] Interrupted during TCP data loop");
            try {
                tcpConn.close();
            } catch (IOException ignored) {
            }
            return ErrHandlerStopped;
        }
    }
}
