package com.proxyblob.protocol;

import com.proxyblob.context.AppContext;
import com.proxyblob.protocol.dto.CryptoResult;
import com.proxyblob.protocol.dto.KeyPair;
import com.proxyblob.protocol.dto.Packet;
import com.proxyblob.proxy.PacketHandler;
import com.proxyblob.proxy.socks.dto.ReceiveResult;
import com.proxyblob.transport.Transport;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.proxyblob.errorcodes.ErrorCodes.ErrConnectionClosed;
import static com.proxyblob.errorcodes.ErrorCodes.ErrConnectionNotFound;
import static com.proxyblob.errorcodes.ErrorCodes.ErrHandlerStopped;
import static com.proxyblob.errorcodes.ErrorCodes.ErrInvalidCommand;
import static com.proxyblob.errorcodes.ErrorCodes.ErrInvalidPacket;
import static com.proxyblob.errorcodes.ErrorCodes.ErrNone;
import static com.proxyblob.errorcodes.ErrorCodes.ErrPacketSendFailed;
import static com.proxyblob.errorcodes.ErrorCodes.ErrTransportClosed;
import static com.proxyblob.errorcodes.ErrorCodes.ErrTransportError;
import static com.proxyblob.protocol.PacketUtil.CmdAck;
import static com.proxyblob.protocol.PacketUtil.CmdClose;
import static com.proxyblob.protocol.PacketUtil.CmdData;
import static com.proxyblob.protocol.PacketUtil.CmdNew;

@Setter
@Getter
@RequiredArgsConstructor
public class BaseHandler {

    private final Transport transport;
    private final PacketHandler packetHandler;
    private final AppContext context;

    private final ConcurrentMap<UUID, Connection> connections = new ConcurrentHashMap<>();

    public void receiveLoop() {
        int consecutiveErrors = 0;
        final int maxConsecutiveErrors = 5;

        while (!context.isStopped()) {
            ReceiveResult result = transport.receive();
            byte[] data = result.getData();
            byte errCode = result.getErrorCode();

            if (errCode != ErrNone) {
                if (transport.isClosed(errCode)) {
                    packetHandler.stop();
                    return;
                }

                if (!context.isStopped() && errCode != ErrTransportError) {
                    consecutiveErrors++;
                    if (consecutiveErrors == maxConsecutiveErrors) {
                        return;
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

            Packet packet = PacketUtil.decode(data);
            if (packet == null) {
                continue;
            }

            byte resultCode = handlePacket(packet);
            if (resultCode != ErrNone) {
                if (!context.isStopped() && resultCode == ErrConnectionClosed) {
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
        KeyPair keyPair = CryptoUtil.generateKeyPair();
        X25519PrivateKeyParameters privateKey = keyPair.getPrivateKey();
        X25519PublicKeyParameters publicKey = keyPair.getPublicKey();

        byte[] privateKeyBytes = privateKey.getEncoded();
        byte[] publicKeyBytes = publicKey.getEncoded();
        byte[] nonce = CryptoUtil.generateNonce();

        Connection conn = connections.get(connectionId);
        if (conn == null) {
            return ErrConnectionNotFound;
        }

        byte[] tempData = new byte[nonce.length + privateKeyBytes.length];
        System.arraycopy(nonce, 0, tempData, 0, nonce.length);
        System.arraycopy(privateKeyBytes, 0, tempData, nonce.length, privateKeyBytes.length);
        conn.setSecretKey(tempData);

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

        KeyPair keyPair = CryptoUtil.generateKeyPair();
        X25519PrivateKeyParameters privateKey = keyPair.getPrivateKey();
        byte[] publicKeyBytes = keyPair.getPublicKey().getEncoded();

        byte[] serverData = conn.getSecretKey();
        if (serverData == null || serverData.length < CryptoUtil.NONCE_SIZE + CryptoUtil.KEY_SIZE) {
            return ErrInvalidPacket;
        }

        byte[] nonce = new byte[CryptoUtil.NONCE_SIZE];
        byte[] serverPublicKeyBytes = new byte[CryptoUtil.KEY_SIZE];

        System.arraycopy(serverData, 0, nonce, 0, CryptoUtil.NONCE_SIZE);
        System.arraycopy(serverData, CryptoUtil.NONCE_SIZE, serverPublicKeyBytes, 0, CryptoUtil.KEY_SIZE);

        X25519PublicKeyParameters serverPublicKey = new X25519PublicKeyParameters(serverPublicKeyBytes, 0);
        CryptoResult result = CryptoUtil.deriveKey(privateKey, serverPublicKey, nonce);

        if (result.getData() == null || result.getData().length != CryptoUtil.KEY_SIZE) {
            return result.getStatus();
        }

        conn.setSecretKey(result.getData());

        return sendPacket(CmdAck, connectionId, publicKeyBytes);
    }

    public byte sendData(UUID connectionId, byte[] data) {
        Connection conn = connections.get(connectionId);
        if (conn == null) {
            return ErrConnectionNotFound;
        }

        CryptoResult result = CryptoUtil.encrypt(conn.getSecretKey(), data);
        if (result.getStatus() != ErrNone) {
            return result.getStatus();
        }

        return sendPacket(CmdData, connectionId, result.getData());
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

        byte[] encoded = PacketUtil.encode(cmd, connectionId, data);
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

            if (!conn.getClosed().get()) {
                conn.close();
            }
        }
    }
}
