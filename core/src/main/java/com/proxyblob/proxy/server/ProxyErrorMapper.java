package com.proxyblob.proxy.server;

import lombok.experimental.UtilityClass;

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

    private final Map<Byte, String> ERROR_MESSAGES = Map.ofEntries(
            Map.entry(ErrNone, "no error"),
            Map.entry(ErrInvalidCommand, "invalid command"),
            Map.entry(ErrContextCanceled, "context canceled"),
            Map.entry(ErrConnectionClosed, "connection closed"),
            Map.entry(ErrConnectionNotFound, "connection not found"),
            Map.entry(ErrConnectionExists, "connection already exists"),
            Map.entry(ErrInvalidState, "invalid connection state"),
            Map.entry(ErrPacketSendFailed, "failed to send packet"),
            Map.entry(ErrHandlerStopped, "handler stopped"),
            Map.entry(ErrUnexpectedPacket, "unexpected packet received"),
            Map.entry(ErrTransportClosed, "transport closed"),
            Map.entry(ErrTransportTimeout, "transport timeout"),
            Map.entry(ErrTransportError, "general transport error"),
            Map.entry(ErrInvalidSocksVersion, "invalid SOCKS version"),
            Map.entry(ErrUnsupportedCommand, "unsupported command"),
            Map.entry(ErrHostUnreachable, "host unreachable"),
            Map.entry(ErrConnectionRefused, "connection refused"),
            Map.entry(ErrNetworkUnreachable, "network unreachable"),
            Map.entry(ErrAddressNotSupported, "address type not supported"),
            Map.entry(ErrTTLExpired, "TTL expired"),
            Map.entry(ErrGeneralSocksFailure, "general SOCKS server failure"),
            Map.entry(ErrAuthFailed, "authentication failed"),
            Map.entry(ErrInvalidPacket, "invalid protocol packet structure"),
            Map.entry(ErrInvalidCrypto, "invalid cryptographic operation")
    );

    public String getMessage(byte errorCode) {
        return ERROR_MESSAGES.getOrDefault(errorCode, "unknown error");
    }
}
