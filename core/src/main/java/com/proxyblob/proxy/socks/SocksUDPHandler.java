package com.proxyblob.proxy.socks;

import com.proxyblob.protocol.BaseHandler;
import com.proxyblob.protocol.Connection;
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

import static com.proxyblob.protocol.Connection.StateConnected;
import static com.proxyblob.protocol.ProtocolError.ErrNetworkUnreachable;
import static com.proxyblob.protocol.ProtocolError.ErrNone;
import static com.proxyblob.protocol.ProtocolError.ErrPacketSendFailed;
import static com.proxyblob.proxy.socks.SocksConstants.IPv4;
import static com.proxyblob.proxy.socks.SocksConstants.Succeeded;
import static com.proxyblob.proxy.socks.SocksConstants.Version5;

@RequiredArgsConstructor
public class SocksUDPHandler {

    private final BaseHandler baseHandler;

    public byte handle(Connection conn) {
        // 1. Create UDP socket (bound to any address, any port)
        DatagramSocket udpSocket;
        try {
            udpSocket = new DatagramSocket(new InetSocketAddress("0.0.0.0", 0));
        } catch (SocketException e) {
            byte errCode = ErrNetworkUnreachable;
            SocksErrorUtil.sendError(baseHandler, conn, errCode);
            return errCode;
        }

        // 2. Get assigned port
        int port = udpSocket.getLocalPort();

        // 3. Build response: |VER|REP|RSV|ATYP|BND.ADDR|BND.PORT|
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        response.write(Version5);         // VER (SOCKS5)
        response.write(Succeeded);         // REP (Succeeded)
        response.write(0);         // RSV
        response.write(IPv4);         // ATYP (IPv4)
        response.writeBytes(new byte[]{0, 0, 0, 0}); // BND.ADDR: 0.0.0.0
        response.write((port >> 8) & 0xFF); // BND.PORT high byte
        response.write(port & 0xFF);        // BND.PORT low byte

        byte errCode = baseHandler.sendData(conn.getId(), response.toByteArray());
        if (errCode != ErrNone) {
            udpSocket.close();
            return ErrPacketSendFailed;
        }

        // 4. Store UDP connection and update state
        conn.setDatagramSocket(udpSocket);
        conn.setState(StateConnected);

        // 5. Start relaying packets
        baseHandler.getContext().getGeneralExecutor().submit(() ->
                new SocksUDPHandler(baseHandler).handleUDPPackets(conn)
        );

        // 6. Keep control TCP connection alive
        while (!baseHandler.getContext().isStopped() && !conn.getClosed().get()) {
            try {
                Thread.sleep(500); // Periodically check
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

        // Ensure proper cleanup
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

        // Track targets
        record TargetInfo(InetSocketAddress addr, Instant lastActive) {}
        ConcurrentHashMap<String, TargetInfo> targets = new ConcurrentHashMap<>();

        // Channel (buffered queue) for incoming responses
        record ResponsePacket(byte[] data, InetSocketAddress addr) {}
        BlockingQueue<ResponsePacket> responses = new LinkedBlockingQueue<>(100);

        baseHandler.getContext().getGeneralExecutor().submit(() -> {
            byte[] respBuf = new byte[128 * 1024];

            while (!baseHandler.getContext().isStopped() && !conn.getClosed().get()) {
                try {
                    targetConn.setSoTimeout(300); // 300ms timeout

                    DatagramPacket packet = new DatagramPacket(respBuf, respBuf.length);
                    try {
                        targetConn.receive(packet);
                    } catch (SocketTimeoutException e) {
                        continue; // Expected due to timeout
                    }

                    // Make a copy since respBuf is reused
                    byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                    InetSocketAddress addr = new InetSocketAddress(packet.getAddress(), packet.getPort());

                    // Try to put into queue (non-blocking)
                    responses.offer(new ResponsePacket(data, addr));

                } catch (IOException e) {
                    if (targetConn.isClosed()) return;
                }
            }
        });

        try {
            Instant lastCleanup = Instant.now();

            while (!baseHandler.getContext().isStopped() && !conn.getClosed().get()) {
                // Приоритет: responses
                ResponsePacket resp = responses.poll();
                if (Instant.now().isAfter(lastCleanup.plusSeconds(30))) {
                    Instant now = Instant.now();
                    targets.entrySet().removeIf(entry -> Duration.between(entry.getValue().lastActive(), now).toMinutes() > 1);
                    lastCleanup = now;
                }

                if (resp != null) {
                    boolean found = false;
                    for (TargetInfo target : targets.values()) {
                        if (target.addr().getAddress().equals(resp.addr().getAddress())
                                && target.addr().getPort() == resp.addr().getPort()) {
                            // Обновить время активности
                            String targetKey = resp.addr().getAddress().getHostAddress() + ":" + resp.addr().getPort();
                            targets.put(targetKey, new TargetInfo(resp.addr(), Instant.now()));
                            found = true;
                            break;
                        }
                    }

                    if (!found || clientAddr == null) {
                        continue;
                    }

                    // Сборка SOCKS-ответа
                    byte addrType;
                    byte[] addrBytes = resp.addr().getAddress().getAddress();
                    if (addrBytes.length == 4) {
                        addrType = (byte) 0x01; // IPv4
                    } else {
                        addrType = (byte) 0x04; // IPv6
                    }

                    ByteArrayOutputStream header = new ByteArrayOutputStream();
                    header.write(new byte[]{0, 0, 0, addrType});
                    header.write(addrBytes);
                    header.write(ByteBuffer.allocate(2).putShort((short) resp.addr().getPort()).array());

                    byte[] fullPacket = ByteBuffer.allocate(header.size() + resp.data().length)
                            .put(header.toByteArray())
                            .put(resp.data())
                            .array();

                    DatagramPacket outPacket = new DatagramPacket(fullPacket, fullPacket.length, clientAddr);
                    try {
                        udpConn.send(outPacket);
                    } catch (IOException e) {
                        if (udpConn.isClosed()) return;
                    }
                    continue;
                }

                // Чтение из клиента
                try {
                    udpConn.setSoTimeout(300);
                    DatagramPacket inPacket = new DatagramPacket(buffer, buffer.length);
                    udpConn.receive(inPacket);

                    InetSocketAddress remoteAddr = new InetSocketAddress(inPacket.getAddress(), inPacket.getPort());

                    // Установить clientAddr, если ещё не установлен
                    if (clientAddr == null) {
                        clientAddr = remoteAddr;
                    }

                    // Только от клиента
                    if (!remoteAddr.getAddress().equals(clientAddr.getAddress())) {
                        continue;
                    }

                    byte[] received = Arrays.copyOf(inPacket.getData(), inPacket.getLength());
                    if (received.length <= 3) continue;

                    SocksAddressParser.Result parsed = SocksAddressParser.extractUDPHeader(received);
                    if (parsed.errorCode() != ErrNone) continue;

                    String targetAddr = parsed.hostAndPort();
                    int headerLen = parsed.consumedBytes();

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
                    // expected timeout
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
