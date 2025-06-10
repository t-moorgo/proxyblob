package com.proxyblob.proxy.socks;

import com.proxyblob.protocol.ProtocolError;
import com.proxyblob.protocol.BaseHandler;
import com.proxyblob.protocol.Connection;
import com.proxyblob.protocol.model.ConnectionState;
import lombok.RequiredArgsConstructor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class SocksUDPHandler {

    private final BaseHandler baseHandler;

    public byte handle(Connection conn) {
        try {
            DatagramSocket socket = new DatagramSocket();
            conn.setDatagramSocket(socket);
            conn.setState(ConnectionState.CONNECTED);

            // UDP Associate response
            byte[] response = new byte[10];
            response[0] = 0x05; // VER
            response[1] = 0x00; // REP
            response[2] = 0x00; // RSV
            response[3] = 0x01; // ATYP IPv4
            System.arraycopy(InetAddress.getLocalHost().getAddress(), 0, response, 4, 4);
            int port = socket.getLocalPort();
            response[8] = (byte) (port >> 8);
            response[9] = (byte) port;

            ProtocolError err = baseHandler.sendData(conn.getId(), response);
            if (err != ProtocolError.NONE) return err.getCode();

            startRelay(conn, socket);
            return ProtocolError.NONE.getCode();

        } catch (IOException e) {
            return ProtocolError.CONNECTION_REFUSED.getCode();
        }
    }

    private void startRelay(Connection conn, DatagramSocket udpSocket) {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        DatagramSocket targetSocket;

        try {
            targetSocket = new DatagramSocket();
        } catch (IOException e) {
            baseHandler.sendClose(conn.getId(), ProtocolError.NETWORK_UNREACHABLE);
            return;
        }

        Map<String, TargetInfo> targets = new ConcurrentHashMap<>();
        InetAddress[] clientAddr = new InetAddress[1];
        int[] clientPort = new int[1];

        executor.submit(() -> {
            byte[] buf = new byte[64 * 1024];

            while (!conn.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(packet);

                    byte[] packetData = Arrays.copyOf(packet.getData(), packet.getLength());

                    if (clientAddr[0] == null) {
                        clientAddr[0] = packet.getAddress();
                        clientPort[0] = packet.getPort();
                    }

                    if (!packet.getAddress().equals(clientAddr[0]) || packet.getPort() != clientPort[0]) {
                        continue;
                    }

                    SocksAddressParser.Result result = SocksAddressParser.parseUDPHeader(packetData);
                    if (result.errorCode() != ProtocolError.NONE.getCode()) {
                        continue;
                    }

                    String targetHostPort = result.hostAndPort();
                    int headerLen = result.consumedBytes();

                    String[] parts = targetHostPort.split(":");
                    InetAddress targetAddress = InetAddress.getByName(parts[0]);
                    int targetPort = Integer.parseInt(parts[1]);

                    targets.put(targetHostPort, new TargetInfo(
                            new InetSocketAddress(targetAddress, targetPort),
                            System.currentTimeMillis()
                    ));

                    DatagramPacket toTarget = new DatagramPacket(
                            packetData, headerLen, packetData.length - headerLen,
                            targetAddress, targetPort
                    );
                    targetSocket.send(toTarget);

                } catch (IOException ignored) {
                }
            }
        });

        executor.submit(() -> {
            byte[] respBuf = new byte[128 * 1024];

            while (!conn.isClosed()) {
                try {
                    targetSocket.setSoTimeout(300);
                    DatagramPacket response = new DatagramPacket(respBuf, respBuf.length);
                    targetSocket.receive(response);

                    String responseKey = response.getAddress().getHostAddress() + ":" + response.getPort();
                    TargetInfo targetInfo = targets.get(responseKey);
                    if (targetInfo == null) continue;

                    targetInfo.lastActive = System.currentTimeMillis();

                    if (clientAddr[0] == null) continue;

                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    out.write(new byte[]{0x00, 0x00, 0x00}); // RSV + FRAG
                    byte atyp = (response.getAddress().getAddress().length == 4) ?
                            SocksConstants.IPV4 : SocksConstants.IPV6;
                    out.write(atyp);
                    out.write(response.getAddress().getAddress());
                    out.write((response.getPort() >> 8) & 0xFF);
                    out.write(response.getPort() & 0xFF);
                    out.write(response.getData(), 0, response.getLength());

                    byte[] fullResp = out.toByteArray();
                    udpSocket.send(new DatagramPacket(
                            fullResp, fullResp.length, clientAddr[0], clientPort[0]
                    ));

                } catch (SocketTimeoutException ignored) {
                } catch (IOException e) {
                    baseHandler.sendClose(conn.getId(), ProtocolError.NETWORK_UNREACHABLE);
                    break;
                }
            }
        });

        // Очистка неактивных таргетов
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            targets.entrySet().removeIf(entry ->
                    now - entry.getValue().lastActive > 60_000
            );
        }, 30, 30, TimeUnit.SECONDS);
    }

    private static class TargetInfo {
        final InetSocketAddress addr;
        volatile long lastActive;

        TargetInfo(InetSocketAddress addr, long lastActive) {
            this.addr = addr;
            this.lastActive = lastActive;
        }
    }
}
