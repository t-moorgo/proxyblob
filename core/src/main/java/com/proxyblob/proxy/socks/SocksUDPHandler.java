package com.proxyblob.proxy.socks;

import com.proxyblob.protocol.BaseHandler;
import com.proxyblob.protocol.Connection;
import com.proxyblob.proxy.socks.dto.ParsedAddress;
import com.proxyblob.proxy.socks.dto.ResponsePacket;
import com.proxyblob.proxy.socks.dto.TargetInfo;
import lombok.RequiredArgsConstructor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import static com.proxyblob.errorcodes.ErrorCodes.ErrNetworkUnreachable;
import static com.proxyblob.errorcodes.ErrorCodes.ErrNone;
import static com.proxyblob.errorcodes.ErrorCodes.ErrPacketSendFailed;
import static com.proxyblob.protocol.Connection.StateConnected;
import static com.proxyblob.proxy.socks.SocksConstants.IPv4;
import static com.proxyblob.proxy.socks.SocksConstants.Succeeded;
import static com.proxyblob.proxy.socks.SocksConstants.Version5;

@RequiredArgsConstructor
public class SocksUDPHandler {

    private final BaseHandler baseHandler;

    public byte handle(Connection conn) {
        DatagramSocket udpSocket;
        try {
            udpSocket = new DatagramSocket(new InetSocketAddress("0.0.0.0", 0));
        } catch (SocketException e) {
            byte errCode = ErrNetworkUnreachable;
            SocksErrorUtil.sendError(baseHandler, conn, errCode);
            return errCode;
        }

        int port = udpSocket.getLocalPort();

        ByteArrayOutputStream response = new ByteArrayOutputStream();
        response.write(Version5);
        response.write(Succeeded);
        response.write(0);
        response.write(IPv4);
        response.writeBytes(new byte[]{0, 0, 0, 0});
        response.write((port >> 8) & 0xFF);
        response.write(port & 0xFF);

        byte errCode = baseHandler.sendData(conn.getId(), response.toByteArray());
        if (errCode != ErrNone) {
            udpSocket.close();
            return ErrPacketSendFailed;
        }

        conn.setDatagramSocket(udpSocket);
        conn.setState(StateConnected);

        baseHandler.getContext().getGeneralExecutor().submit(() ->
                new SocksUDPHandler(baseHandler).handleUDPPackets(conn)
        );

        while (!baseHandler.getContext().isStopped() && !conn.getClosed().get()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                break;
            }
        }

        udpSocket.close();
        return ErrNone;
    }

    public void handleUDPPackets(Connection conn) {
        DatagramSocket udpConn = conn.getDatagramSocket();
        byte[] buffer = new byte[64 * 1024];
        InetSocketAddress clientAddr = null;

        DatagramSocket targetConn;
        try {
            targetConn = new DatagramSocket();
        } catch (SocketException e) {
            baseHandler.sendClose(conn.getId(), ErrNetworkUnreachable);
            return;
        }

        baseHandler.getContext().getGeneralExecutor().submit(() -> {
            try {
                while (!baseHandler.getContext().isStopped() && !conn.getClosed().get()) {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ignored) {
            } finally {
                targetConn.close();
            }
        });

        ConcurrentHashMap<String, TargetInfo> targets = new ConcurrentHashMap<>();

        BlockingQueue<ResponsePacket> responses = new LinkedBlockingQueue<>(100);

        baseHandler.getContext().getGeneralExecutor().submit(() -> {
            byte[] respBuf = new byte[128 * 1024];

            while (!baseHandler.getContext().isStopped() && !conn.getClosed().get()) {
                try {
                    targetConn.setSoTimeout(300);

                    DatagramPacket packet = new DatagramPacket(respBuf, respBuf.length);
                    try {
                        targetConn.receive(packet);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }

                    byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                    InetSocketAddress addr = new InetSocketAddress(packet.getAddress(), packet.getPort());

                    responses.offer(new ResponsePacket(data, addr));

                } catch (IOException e) {
                    if (targetConn.isClosed()) return;
                }
            }
        });

        try {
            Instant lastCleanup = Instant.now();

            while (!baseHandler.getContext().isStopped() && !conn.getClosed().get()) {
                ResponsePacket resp = responses.poll();
                if (Instant.now().isAfter(lastCleanup.plusSeconds(30))) {
                    Instant now = Instant.now();
                    targets.entrySet().removeIf(entry -> Duration.between(entry.getValue().getLastActive(), now).toMinutes() > 1);
                    lastCleanup = now;
                }

                if (resp != null) {
                    boolean found = false;
                    for (TargetInfo target : targets.values()) {
                        if (target.getAddr().getAddress().equals(resp.getAddr().getAddress())
                                && target.getAddr().getPort() == resp.getAddr().getPort()) {
                            String targetKey = resp.getAddr().getAddress().getHostAddress() + ":" + resp.getAddr().getPort();
                            targets.put(targetKey, new TargetInfo(resp.getAddr(), Instant.now()));
                            found = true;
                            break;
                        }
                    }

                    if (!found || clientAddr == null) {
                        continue;
                    }

                    byte addrType;
                    byte[] addrBytes = resp.getAddr().getAddress().getAddress();
                    if (addrBytes.length == 4) {
                        addrType = (byte) 0x01;
                    } else {
                        addrType = (byte) 0x04;
                    }

                    ByteArrayOutputStream header = new ByteArrayOutputStream();
                    header.write(new byte[]{0, 0, 0, addrType});
                    header.write(addrBytes);
                    header.write(ByteBuffer.allocate(2).putShort((short) resp.getAddr().getPort()).array());

                    byte[] fullPacket = ByteBuffer.allocate(header.size() + resp.getData().length)
                            .put(header.toByteArray())
                            .put(resp.getData())
                            .array();

                    DatagramPacket outPacket = new DatagramPacket(fullPacket, fullPacket.length, clientAddr);
                    try {
                        udpConn.send(outPacket);
                    } catch (IOException e) {
                        if (udpConn.isClosed()) return;
                    }
                    continue;
                }

                try {
                    udpConn.setSoTimeout(300);
                    DatagramPacket inPacket = new DatagramPacket(buffer, buffer.length);
                    udpConn.receive(inPacket);

                    InetSocketAddress remoteAddr = new InetSocketAddress(inPacket.getAddress(), inPacket.getPort());

                    if (clientAddr == null) {
                        clientAddr = remoteAddr;
                    }

                    if (!remoteAddr.getAddress().equals(clientAddr.getAddress())) {
                        continue;
                    }

                    byte[] received = Arrays.copyOf(inPacket.getData(), inPacket.getLength());
                    if (received.length <= 3) continue;

                    ParsedAddress parsed = SocksAddressParser.extractUDPHeader(received);
                    if (parsed.getErrorCode() != ErrNone) continue;

                    String targetAddr = parsed.getHostAndPort();
                    int headerLen = parsed.getConsumedBytes();

                    String[] parts = targetAddr.split(":");
                    if (parts.length != 2) continue;
                    InetSocketAddress targetUDPAddr;
                    try {
                        targetUDPAddr = new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
                    } catch (NumberFormatException e) {
                        continue;
                    }

                    String targetKey = targetUDPAddr.getAddress().getHostAddress() + ":" + targetUDPAddr.getPort();
                    targets.put(targetKey, new TargetInfo(targetUDPAddr, Instant.now()));

                    byte[] payload = Arrays.copyOfRange(received, headerLen, received.length);
                    DatagramPacket targetPacket = new DatagramPacket(payload, payload.length, targetUDPAddr);
                    targetConn.send(targetPacket);
                } catch (SocketTimeoutException ignore) {
                    //TODO что то сделать
                } catch (IOException e) {
                    baseHandler.sendClose(conn.getId(), ErrNetworkUnreachable);
                    return;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            udpConn.close();
        }
    }
}
