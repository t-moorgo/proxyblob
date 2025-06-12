package com.proxyblob.proxy.server;

import lombok.experimental.UtilityClass;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.proxyblob.errorcodes.ErrorCodes.ErrAddressNotSupported;
import static com.proxyblob.errorcodes.ErrorCodes.ErrAuthFailed;
import static com.proxyblob.errorcodes.ErrorCodes.ErrConnectionClosed;
import static com.proxyblob.errorcodes.ErrorCodes.ErrConnectionExists;
import static com.proxyblob.errorcodes.ErrorCodes.ErrConnectionNotFound;
import static com.proxyblob.errorcodes.ErrorCodes.ErrConnectionRefused;
import static com.proxyblob.errorcodes.ErrorCodes.ErrContextCanceled;
import static com.proxyblob.errorcodes.ErrorCodes.ErrGeneralSocksFailure;
import static com.proxyblob.errorcodes.ErrorCodes.ErrHandlerStopped;
import static com.proxyblob.errorcodes.ErrorCodes.ErrHostUnreachable;
import static com.proxyblob.errorcodes.ErrorCodes.ErrInvalidCommand;
import static com.proxyblob.errorcodes.ErrorCodes.ErrInvalidCrypto;
import static com.proxyblob.errorcodes.ErrorCodes.ErrInvalidPacket;
import static com.proxyblob.errorcodes.ErrorCodes.ErrInvalidSocksVersion;
import static com.proxyblob.errorcodes.ErrorCodes.ErrInvalidState;
import static com.proxyblob.errorcodes.ErrorCodes.ErrNetworkUnreachable;
import static com.proxyblob.errorcodes.ErrorCodes.ErrNone;
import static com.proxyblob.errorcodes.ErrorCodes.ErrPacketSendFailed;
import static com.proxyblob.errorcodes.ErrorCodes.ErrTTLExpired;
import static com.proxyblob.errorcodes.ErrorCodes.ErrTransportClosed;
import static com.proxyblob.errorcodes.ErrorCodes.ErrTransportError;
import static com.proxyblob.errorcodes.ErrorCodes.ErrTransportTimeout;
import static com.proxyblob.errorcodes.ErrorCodes.ErrUnexpectedPacket;
import static com.proxyblob.errorcodes.ErrorCodes.ErrUnsupportedCommand;

@UtilityClass
public class ProxyErrorMapper {

    private static final Map<Byte, String> ERROR_MESSAGES;

    static {
        Map<Byte, String> map = new HashMap<>();

        map.put(ErrNone, "no error");
        map.put(ErrInvalidCommand, "invalid command");
        map.put(ErrContextCanceled, "context canceled");
        map.put(ErrConnectionClosed, "connection closed");
        map.put(ErrConnectionNotFound, "connection not found");
        map.put(ErrConnectionExists, "connection already exists");
        map.put(ErrInvalidState, "invalid connection state");
        map.put(ErrPacketSendFailed, "failed to send packet");
        map.put(ErrHandlerStopped, "handler stopped");
        map.put(ErrUnexpectedPacket, "unexpected packet received");
        map.put(ErrTransportClosed, "transport closed");
        map.put(ErrTransportTimeout, "transport timeout");
        map.put(ErrTransportError, "general transport error");
        map.put(ErrInvalidSocksVersion, "invalid SOCKS version");
        map.put(ErrUnsupportedCommand, "unsupported command");
        map.put(ErrHostUnreachable, "host unreachable");
        map.put(ErrConnectionRefused, "connection refused");
        map.put(ErrNetworkUnreachable, "network unreachable");
        map.put(ErrAddressNotSupported, "address type not supported");
        map.put(ErrTTLExpired, "TTL expired");
        map.put(ErrGeneralSocksFailure, "general SOCKS server failure");
        map.put(ErrAuthFailed, "authentication failed");
        map.put(ErrInvalidPacket, "invalid protocol packet structure");
        map.put(ErrInvalidCrypto, "invalid cryptographic operation");

        ERROR_MESSAGES = Collections.unmodifiableMap(map);
    }

    public static String getMessage(byte errorCode) {
        return ERROR_MESSAGES.getOrDefault(errorCode, "unknown error");
    }
}
