package com.proxyblob.protocol;

import com.proxyblob.context.AppContext;
import com.proxyblob.proxy.PacketHandler;
import com.proxyblob.transport.Transport;
import lombok.Getter;
import lombok.Setter;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.proxyblob.protocol.Packet.CmdAck;
import static com.proxyblob.protocol.Packet.CmdClose;
import static com.proxyblob.protocol.Packet.CmdData;
import static com.proxyblob.protocol.Packet.CmdNew;
import static com.proxyblob.protocol.ProtocolError.ErrConnectionNotFound;
import static com.proxyblob.protocol.ProtocolError.ErrHandlerStopped;
import static com.proxyblob.protocol.ProtocolError.ErrInvalidCommand;
import static com.proxyblob.protocol.ProtocolError.ErrInvalidCrypto;
import static com.proxyblob.protocol.ProtocolError.ErrInvalidPacket;
import static com.proxyblob.protocol.ProtocolError.ErrNone;
import static com.proxyblob.protocol.ProtocolError.ErrPacketSendFailed;
import static com.proxyblob.protocol.ProtocolError.ErrTransportClosed;

@Setter
@Getter
public class BaseHandler {

    private final Transport transport;

    // Equivalent of sync.Map<UUID, Connection> in Go
    private final ConcurrentMap<UUID, Connection> connections = new ConcurrentHashMap<>();

    // Cancellation support
    private final AppContext context;

    // Delegated packet handler
    private PacketHandler packetHandler;

    public BaseHandler(Transport transport, PacketHandler packetHandler, AppContext context) {
        this.transport = transport;
        this.packetHandler = packetHandler;
        this.context = context;
    }


    public void start() {
        context.getReceiverExecutor().submit(this::receiveLoop);
    }

    public void stop() {
        context.stop();
    }

    public void receiveLoop() {
        int consecutiveErrors = 0;
        final int maxConsecutiveErrors = 5;

        while (!context.isStopped()) {
            Transport.ReceiveResult result = transport.receive();
            byte[] data = result.data();
            byte errCode = result.errorCode();

            if (errCode != ErrNone) {
                if (transport.isClosed(errCode)) {
                    packetHandler.stop(); // завершение, если закрыт транспорт
                    return;
                }

                if (!context.isStopped() && errCode != ProtocolError.ErrTransportError) {
                    consecutiveErrors++;
                    if (consecutiveErrors == maxConsecutiveErrors) {
                        return; // слишком много ошибок подряд
                    }
                    try {
                        Thread.sleep(consecutiveErrors * 50L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                continue;
            }

            consecutiveErrors = 0;

            if (data == null || data.length == 0) {
                continue;
            }

            Packet packet = Packet.decode(data);
            if (packet == null) {
                continue;
            }

            byte resultCode = handlePacket(packet);
            if (resultCode != ErrNone) {
                if (!context.isStopped() && resultCode == ProtocolError.ErrConnectionClosed) {
                    continue;
                }
                sendClose(packet.getConnectionId(), resultCode);
            }
        }
    }

    private byte handlePacket(Packet packet) {
        return switch (packet.getCommand()) {
            case CmdNew -> packetHandler.onNew(packet.getConnectionId(), packet.getData());
            case CmdAck -> packetHandler.onAck(packet.getConnectionId(), packet.getData());
            case CmdData -> packetHandler.onData(packet.getConnectionId(), packet.getData());
            case CmdClose -> {
                byte reason = packet.getData().length > 0 ? packet.getData()[0] : ErrInvalidCommand;
                yield packetHandler.onClose(packet.getConnectionId(), reason);
            }
            default -> ErrInvalidCommand;
        };
    }

    public byte sendNewConnection(UUID connectionId) {
        CryptoUtil.KeyPair keyPair = CryptoUtil.generateKeyPair();
        X25519PrivateKeyParameters privateKey = keyPair.privateKey();
        X25519PublicKeyParameters publicKey = keyPair.publicKey();

        byte[] privateKeyBytes = privateKey.getEncoded(); // 32 bytes
        byte[] publicKeyBytes = publicKey.getEncoded();   // 32 bytes
        byte[] nonce = CryptoUtil.generateNonce();        // 24 bytes

        Connection conn = connections.get(connectionId);
        if (conn == null) {
            return ErrConnectionNotFound;
        }

        // Store (nonce + privateKey) for future use in ACK
        byte[] tempData = new byte[nonce.length + privateKeyBytes.length];
        System.arraycopy(nonce, 0, tempData, 0, nonce.length);
        System.arraycopy(privateKeyBytes, 0, tempData, nonce.length, privateKeyBytes.length);
        conn.setSecretKey(tempData);

        // Prepare data to send: (nonce + publicKey)
        byte[] data = new byte[nonce.length + publicKeyBytes.length];
        System.arraycopy(nonce, 0, data, 0, nonce.length);
        System.arraycopy(publicKeyBytes, 0, data, nonce.length, publicKeyBytes.length);

        return sendPacket(CmdNew, connectionId, data);
    }

    public byte sendConnAck(UUID connectionId) {
        Connection conn = connections.get(connectionId);
        if (conn == null) {
            return ErrConnectionNotFound;
        }

        // Генерация новой пары ключей
        CryptoUtil.KeyPair keyPair = CryptoUtil.generateKeyPair();
        X25519PrivateKeyParameters privateKey = keyPair.privateKey();
        byte[] publicKeyBytes = keyPair.publicKey().getEncoded();

        // Получение nonce и публичного ключа сервера из сохранённого SecretKey
        byte[] serverData = conn.getSecretKey();
        if (serverData == null || serverData.length < CryptoUtil.NONCE_SIZE + CryptoUtil.KEY_SIZE) {
            return ErrInvalidPacket;
        }

        byte[] nonce = new byte[CryptoUtil.NONCE_SIZE];
        byte[] serverPublicKeyBytes = new byte[CryptoUtil.KEY_SIZE];

        System.arraycopy(serverData, 0, nonce, 0, CryptoUtil.NONCE_SIZE);
        System.arraycopy(serverData, CryptoUtil.NONCE_SIZE, serverPublicKeyBytes, 0, CryptoUtil.KEY_SIZE);

        X25519PublicKeyParameters serverPublicKey = new X25519PublicKeyParameters(serverPublicKeyBytes, 0);
        byte[] symmetricKey = CryptoUtil.deriveKey(privateKey, serverPublicKey, nonce);

        if (symmetricKey == null || symmetricKey.length != CryptoUtil.KEY_SIZE) {
            return ErrInvalidCrypto;
        }

        conn.setSecretKey(symmetricKey);

        // Отправляем публичный ключ в ACK
        return sendPacket(CmdAck, connectionId, publicKeyBytes);
    }

    public byte sendData(UUID connectionId, byte[] data) {
        Connection conn = connections.get(connectionId);
        if (conn == null) {
            return ErrConnectionNotFound;
        }

        CryptoUtil.CryptoResult result = CryptoUtil.encrypt(conn.getSecretKey(), data);
        if (result.status() != CryptoUtil.CryptoStatus.OK) {
            return ErrInvalidCrypto;
        }

        return sendPacket(CmdData, connectionId, result.data());
    }


    public byte sendClose(UUID connectionId, byte errorCode) {
        Connection conn = connections.get(connectionId);
        if (conn == null) {
            return ErrConnectionNotFound;
        }

        conn.close();
        return sendPacket(CmdClose, connectionId, new byte[]{errorCode});
    }

    private byte sendPacket(byte cmd, UUID connectionId, byte[] data) {
        if (context.isStopped()) {
            return ErrHandlerStopped;
        }

        Packet packet = new Packet(cmd, connectionId, data);
        if (packet == null) {
            return ErrInvalidPacket;
        }

        byte[] encoded = packet.encode();
        if (encoded == null) {
            return ErrInvalidPacket;
        }

        byte errCode = transport.send(encoded);
        if (errCode != ErrNone) {
            if (transport.isClosed(errCode)) {
                return ErrTransportClosed;
            }
            return ErrPacketSendFailed;
        }

        return ErrNone;
    }

    public void closeAllConnections() {
        for (Map.Entry<UUID, Connection> entry : connections.entrySet()) {
            Connection conn = entry.getValue();

            // Only close if not already closed
            if (!conn.getClosed().get()) {
                conn.close();
            }
        }
    }

}
