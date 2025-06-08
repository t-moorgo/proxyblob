package com.proxyblob.protocol.handler;

import com.proxyblob.protocol.crypto.CipherUtil;
import com.proxyblob.protocol.crypto.KeyUtil;
import com.proxyblob.protocol.error.ProtocolError;
import com.proxyblob.protocol.model.Command;
import com.proxyblob.protocol.model.Connection;
import com.proxyblob.protocol.model.Packet;
import com.proxyblob.proxy.PacketHandler;
import com.proxyblob.transport.Transport;
import com.proxyblob.transport.exception.TransportCanceledException;
import com.proxyblob.transport.exception.TransportException;
import lombok.RequiredArgsConstructor;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiredArgsConstructor
public class BaseHandler {

    private final Transport transport;

    private PacketHandler packetHandler;

    private final ConnectionManager connectionManager = new ConnectionManager();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public void registerPacketHandler(PacketHandler handler) {
        this.packetHandler = handler;
    }

    public void start() {
        executor.submit(this::receiveLoop);
    }

    public void stop() {
        running.set(false);
        executor.shutdownNow();
        connectionManager.closeAll();
    }

    public void receiveLoop() {
        int consecutiveErrors = 0;
        int maxConsecutiveErrors = 5;

        while (running.get()) {
            try {
                byte[] received = transport.receive(running);
                consecutiveErrors = 0;

                if (received == null || received.length == 0) continue;

                Packet packet = Packet.decode(received);
                if (packet == null) continue;

                ProtocolError error = handlePacket(packet);
                if (error != ProtocolError.NONE) {
                    sendClose(packet.getConnectionId(), error);
                }

            } catch (TransportException e) {
                if (e instanceof TransportCanceledException) {
                    break;
                }

                consecutiveErrors++;
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    break; // too many errors, exit loop
                }

                try {
                    Thread.sleep(consecutiveErrors * 50L);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
    }

    public ProtocolError sendNewConnection(UUID connectionId) {
        var keyPair = KeyUtil.generateKeyPair();
        byte[] nonce = KeyUtil.generateNonce();

        Connection conn = connectionManager.get(connectionId);
        if (conn == null) return ProtocolError.CONNECTION_NOT_FOUND;

        byte[] tmp = new byte[nonce.length + KeyUtil.KEY_SIZE];
        System.arraycopy(nonce, 0, tmp, 0, nonce.length);
        System.arraycopy(keyPair.privateKey().getEncoded(), 0, tmp, nonce.length, KeyUtil.KEY_SIZE);
        conn.setSecretKey(tmp);

        byte[] publicKey = keyPair.publicKey().getEncoded();
        byte[] payload = new byte[nonce.length + publicKey.length];
        System.arraycopy(nonce, 0, payload, 0, nonce.length);
        System.arraycopy(publicKey, 0, payload, nonce.length, publicKey.length);

        return sendPacket(Command.NEW.getCode(), connectionId, payload);
    }

    public ProtocolError sendConnAck(UUID connectionId, byte[] serverPubKeyAndNonce) {
        // serverPubKeyAndNonce = nonce (24 bytes) || server public key (32 bytes)
        var keyPair = KeyUtil.generateKeyPair();
        byte[] serverNonce = new byte[KeyUtil.NONCE_SIZE];
        byte[] serverPublicKey = new byte[KeyUtil.KEY_SIZE];
        System.arraycopy(serverPubKeyAndNonce, 0, serverNonce, 0, KeyUtil.NONCE_SIZE);
        System.arraycopy(serverPubKeyAndNonce, KeyUtil.NONCE_SIZE, serverPublicKey, 0, KeyUtil.KEY_SIZE);

        Connection conn = connectionManager.get(connectionId);
        if (conn == null) return ProtocolError.CONNECTION_NOT_FOUND;

        byte[] symmetricKey = KeyUtil.deriveSharedKey(
                keyPair.privateKey(),
                new org.bouncycastle.crypto.params.X25519PublicKeyParameters(serverPublicKey, 0),
                serverNonce
        );
        conn.setSecretKey(symmetricKey);

        return sendPacket(Command.ACK.getCode(), connectionId, keyPair.publicKey().getEncoded());
    }

    public ProtocolError sendData(UUID connectionId, byte[] data) {
        Connection conn = connectionManager.get(connectionId);
        if (conn == null) return ProtocolError.CONNECTION_NOT_FOUND;

        try {
            byte[] encrypted = CipherUtil.encrypt(conn.getSecretKey(), data);
            return sendPacket(Command.DATA.getCode(), connectionId, encrypted);
        } catch (Exception e) {
            return ProtocolError.INVALID_CRYPTO;
        }
    }

    public ProtocolError sendClose(UUID connectionId, ProtocolError error) {
        Connection conn = connectionManager.get(connectionId);
        if (conn == null) return ProtocolError.CONNECTION_NOT_FOUND;

        conn.close();
        return sendPacket(Command.CLOSE.getCode(), connectionId, new byte[]{error.getCode()});
    }

    public void addConnection(Connection connection) {
        connectionManager.add(connection);
    }

    public void removeConnection(UUID id) {
        connectionManager.remove(id);
    }

    public void closeAllConnections() {
        connectionManager.closeAll();
    }

    public Connection getConnection(UUID id) {
        return connectionManager.get(id);
    }

    private ProtocolError handlePacket(Packet packet) {
        Command cmd = Command.fromByte(packet.getCommand());
        if (cmd == null) return ProtocolError.INVALID_COMMAND;

        return switch (cmd) {
            case NEW -> packetHandler.onNew(packet.getConnectionId(), packet.getData());
            case ACK -> packetHandler.onAck(packet.getConnectionId(), packet.getData());
            case DATA -> packetHandler.onData(packet.getConnectionId(), packet.getData());
            case CLOSE -> {
                byte code = packet.getData().length > 0 ? packet.getData()[0] : 0;
                yield packetHandler.onClose(packet.getConnectionId(), code);
            }
        };
    }

    private ProtocolError sendPacket(byte cmd, UUID connectionId, byte[] data) {
        if (!running.get()) return ProtocolError.HANDLER_STOPPED;

        Packet packet = new Packet(cmd, connectionId, data);
        byte[] encoded = packet.encode();
        if (encoded == null) return ProtocolError.INVALID_PACKET;

        try {
            transport.send(running, encoded);
            return ProtocolError.NONE;
        } catch (TransportException e) {
            return ProtocolError.PACKET_SEND_FAILED;
        }
    }
}
