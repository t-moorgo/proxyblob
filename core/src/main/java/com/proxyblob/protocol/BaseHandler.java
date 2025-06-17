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
                System.out.println("[BaseHandler] receive error: " + errCode);
                if (transport.isClosed(errCode)) {
                    System.out.println("[BaseHandler] transport closed, stopping packet handler");
                    packetHandler.stop();
                    return;
                }

                if (!context.isStopped() && errCode != ErrTransportError) {
                    consecutiveErrors++;
                    if (consecutiveErrors == maxConsecutiveErrors) {
                        System.out.println("[BaseHandler] too many errors, exiting loop");
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
                System.out.println("[BaseHandler] received empty packet");
                continue;
            }

            System.out.println("[BaseHandler] received packet, size: " + data.length);
            Packet packet = PacketUtil.decode(data);
            if (packet == null) {
                System.out.println("[BaseHandler] failed to decode packet");
                continue;
            }

            System.out.println("[BaseHandler] decoded packet: command=" + packet.getCommand() + ", connId=" + packet.getConnectionId());
            byte resultCode = handlePacket(packet);
            if (resultCode != ErrNone) {
                System.out.println("[BaseHandler] packet handler returned error: " + resultCode);
                if (!context.isStopped() && resultCode == ErrConnectionClosed) {
                    continue;
                }
                sendClose(packet.getConnectionId(), resultCode);
            }
        }
    }

    private byte handlePacket(Packet packet) {
        byte command = packet.getCommand();
        System.out.println("[BaseHandler] handling packet command: " + command);

        return switch (command) {
            case CmdNew -> packetHandler.onNew(packet.getConnectionId(), packet.getData());
            case CmdAck -> packetHandler.onAck(packet.getConnectionId(), packet.getData());
            case CmdData -> packetHandler.onData(packet.getConnectionId(), packet.getData());
            case CmdClose -> {
                byte reason = packet.getData().length > 0 ? packet.getData()[0] : ErrInvalidCommand;
                yield packetHandler.onClose(packet.getConnectionId(), reason);
            }
            default -> {
                System.out.println("[BaseHandler] unknown command: " + command);
                yield ErrInvalidCommand;
            }
        };
    }

    public byte sendNewConnection(UUID connectionId) {
        System.out.println("[BaseHandler] sendNewConnection: " + connectionId);

        // Генерируем пару ключей X25519
        KeyPair keyPair = CryptoUtil.generateKeyPair();
        byte[] privateKeyBytes = keyPair.getPrivateKey().getEncoded();
        byte[] publicKeyBytes = keyPair.getPublicKey().getEncoded();
        byte[] nonce = CryptoUtil.generateNonce();

        // Получаем соединение
        Connection conn = connections.get(connectionId);
        if (conn == null) {
            System.out.println("[BaseHandler] connection not found: " + connectionId);
            return ErrConnectionNotFound;
        }

        // Собираем данные (nonce + privateKey), сохраняем как secretKey
        byte[] secretData = new byte[nonce.length + privateKeyBytes.length];
        System.arraycopy(nonce, 0, secretData, 0, nonce.length);
        System.arraycopy(privateKeyBytes, 0, secretData, nonce.length, privateKeyBytes.length);
        conn.setSecretKey(secretData);

        // Отправляем клиенту (агенту) CmdNew с nonce + publicKey
        byte[] dataToSend = new byte[nonce.length + publicKeyBytes.length];
        System.arraycopy(nonce, 0, dataToSend, 0, nonce.length);
        System.arraycopy(publicKeyBytes, 0, dataToSend, nonce.length, publicKeyBytes.length);
        System.out.println("privateKey.length = " + privateKeyBytes.length); // 32
        System.out.println("nonce.length = " + nonce.length); // 12
        System.out.println("secret.length = " + secretData.length); // 44


        return sendPacket(CmdNew, connectionId, dataToSend);
    }

    public byte sendConnAck(UUID connectionId) {
        System.out.println("[BaseHandler] sendConnAck: " + connectionId);
        Connection conn = connections.get(connectionId);
        if (conn == null) {
            System.out.println("[BaseHandler] connection not found: " + connectionId);
            return ErrConnectionNotFound;
        }

        KeyPair keyPair = CryptoUtil.generateKeyPair();
        X25519PrivateKeyParameters privateKey = keyPair.getPrivateKey();
        byte[] publicKeyBytes = keyPair.getPublicKey().getEncoded();

        byte[] serverData = conn.getSecretKey();
        if (serverData == null || serverData.length < CryptoUtil.NONCE_SIZE + CryptoUtil.KEY_SIZE) {
            System.out.println("[BaseHandler] invalid server data for key exchange");
            return ErrInvalidPacket;
        }

        byte[] nonce = new byte[CryptoUtil.NONCE_SIZE];
        byte[] serverPublicKeyBytes = new byte[CryptoUtil.KEY_SIZE];

        System.arraycopy(serverData, 0, nonce, 0, CryptoUtil.NONCE_SIZE);
        System.arraycopy(serverData, CryptoUtil.NONCE_SIZE, serverPublicKeyBytes, 0, CryptoUtil.KEY_SIZE);

        X25519PublicKeyParameters serverPublicKey = new X25519PublicKeyParameters(serverPublicKeyBytes, 0);
        CryptoResult result = CryptoUtil.deriveKey(privateKey, serverPublicKey, nonce);

        if (result.getData() == null || result.getData().length != CryptoUtil.KEY_SIZE) {
            System.out.println("[BaseHandler] key derivation failed");
            return result.getStatus();
        }

        conn.setSecretKey(result.getData());

        return sendPacket(CmdAck, connectionId, publicKeyBytes);
    }

    public byte sendData(UUID connectionId, byte[] data) {
        System.out.println("[BaseHandler] sendData: " + connectionId + ", length=" + data.length);
        Connection conn = connections.get(connectionId);
        if (conn == null) {
            return ErrConnectionNotFound;
        }

        CryptoResult result = CryptoUtil.encrypt(conn.getSecretKey(), data);
        if (result.getStatus() != ErrNone) {
            System.out.println("[BaseHandler] encryption failed with code: " + result.getStatus());
            return result.getStatus();
        }

        return sendPacket(CmdData, connectionId, result.getData());
    }

    public byte sendClose(UUID connectionId, byte errorCode) {
        System.out.println("[BaseHandler] sendClose: " + connectionId + ", errorCode=" + errorCode);
        Connection conn = connections.get(connectionId);
        if (conn == null) {
            return ErrConnectionNotFound;
        }

        conn.close();
        return sendPacket(CmdClose, connectionId, new byte[]{errorCode});
    }

    private byte sendPacket(byte cmd, UUID connectionId, byte[] data) {
        if (context.isStopped()) {
            System.out.println("[BaseHandler] context is stopped, cannot send packet");
            return ErrHandlerStopped;
        }

        byte[] encoded = PacketUtil.encode(cmd, connectionId, data);
        if (encoded == null) {
            System.out.println("[BaseHandler] failed to encode packet");
            return ErrInvalidPacket;
        }

        byte errCode = transport.send(encoded);
        if (errCode != ErrNone) {
            System.out.println("[BaseHandler] send failed with error code: " + errCode);
            if (transport.isClosed(errCode)) {
                return ErrTransportClosed;
            }
            return ErrPacketSendFailed;
        }

        System.out.println("[BaseHandler] packet sent: cmd=" + cmd + ", connId=" + connectionId + ", len=" + data.length);
        return ErrNone;
    }

    public void closeAllConnections() {
        for (Map.Entry<UUID, Connection> entry : connections.entrySet()) {
            Connection conn = entry.getValue();

            if (!conn.getClosed().get()) {
                System.out.println("[BaseHandler] closing connection: " + entry.getKey());
                conn.close();
            }
        }
    }
}
