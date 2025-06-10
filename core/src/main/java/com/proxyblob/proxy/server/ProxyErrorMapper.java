package com.proxyblob.proxy.server;

import com.proxyblob.protocol.ProtocolError;
import lombok.experimental.UtilityClass;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@UtilityClass
public class ProxyErrorMapper {
    private static final Map<Byte, String> ERROR_MESSAGES;

    static {
        Map<Byte, String> map = new HashMap<>();

        // General errors
        map.put(ProtocolError.ErrNone, "no error");
        map.put(ProtocolError.ErrInvalidCommand, "invalid command");
        map.put(ProtocolError.ErrContextCanceled, "context canceled");

        // Connection state errors
        map.put(ProtocolError.ErrConnectionClosed, "connection closed");
        map.put(ProtocolError.ErrConnectionNotFound, "connection not found");
        map.put(ProtocolError.ErrConnectionExists, "connection already exists");
        map.put(ProtocolError.ErrInvalidState, "invalid connection state");
        map.put(ProtocolError.ErrPacketSendFailed, "failed to send packet");
        map.put(ProtocolError.ErrHandlerStopped, "handler stopped");
        map.put(ProtocolError.ErrUnexpectedPacket, "unexpected packet received");

        // Transport layer errors
        map.put(ProtocolError.ErrTransportClosed, "transport closed");
        map.put(ProtocolError.ErrTransportTimeout, "transport timeout");
        map.put(ProtocolError.ErrTransportError, "general transport error");

        // SOCKS reply codes
        map.put(ProtocolError.ErrInvalidSocksVersion, "invalid SOCKS version");
        map.put(ProtocolError.ErrUnsupportedCommand, "unsupported command");
        map.put(ProtocolError.ErrHostUnreachable, "host unreachable");
        map.put(ProtocolError.ErrConnectionRefused, "connection refused");
        map.put(ProtocolError.ErrNetworkUnreachable, "network unreachable");
        map.put(ProtocolError.ErrAddressNotSupported, "address type not supported");
        map.put(ProtocolError.ErrTTLExpired, "TTL expired");
        map.put(ProtocolError.ErrGeneralSocksFailure, "general SOCKS server failure");
        map.put(ProtocolError.ErrAuthFailed, "authentication failed");

        // Protocol packet errors
        map.put(ProtocolError.ErrInvalidPacket, "invalid protocol packet structure");
        map.put(ProtocolError.ErrInvalidCrypto, "invalid cryptographic operation");

        ERROR_MESSAGES = Collections.unmodifiableMap(map);
    }

    public static String getMessage(byte errorCode) {
        return ERROR_MESSAGES.getOrDefault(errorCode, "unknown error");
    }
}
