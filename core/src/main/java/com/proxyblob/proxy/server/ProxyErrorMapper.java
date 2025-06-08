package com.proxyblob.proxy.server;

import com.proxyblob.protocol.error.ProtocolError;
import lombok.experimental.UtilityClass;

import java.util.HashMap;
import java.util.Map;

@UtilityClass
public class ProxyErrorMapper {
    private static final Map<Byte, String> ERROR_MESSAGES = new HashMap<>();

    static {
        // General errors
        ERROR_MESSAGES.put(ProtocolError.NONE.getCode(), "no error");
        ERROR_MESSAGES.put(ProtocolError.INVALID_COMMAND.getCode(), "invalid command");
        ERROR_MESSAGES.put(ProtocolError.CONTEXT_CANCELED.getCode(), "context canceled");

        // Connection state errors
        ERROR_MESSAGES.put(ProtocolError.CONNECTION_CLOSED.getCode(), "connection closed");
        ERROR_MESSAGES.put(ProtocolError.CONNECTION_NOT_FOUND.getCode(), "connection not found");
        ERROR_MESSAGES.put(ProtocolError.CONNECTION_EXISTS.getCode(), "connection already exists");
        ERROR_MESSAGES.put(ProtocolError.INVALID_STATE.getCode(), "invalid connection state");
        ERROR_MESSAGES.put(ProtocolError.PACKET_SEND_FAILED.getCode(), "failed to send packet");
        ERROR_MESSAGES.put(ProtocolError.HANDLER_STOPPED.getCode(), "handler stopped");
        ERROR_MESSAGES.put(ProtocolError.UNEXPECTED_PACKET.getCode(), "unexpected packet received");

        // Transport layer errors
        ERROR_MESSAGES.put(ProtocolError.TRANSPORT_CLOSED.getCode(), "transport closed");
        ERROR_MESSAGES.put(ProtocolError.TRANSPORT_TIMEOUT.getCode(), "transport timeout");
        ERROR_MESSAGES.put(ProtocolError.TRANSPORT_ERROR.getCode(), "general transport error");

        // SOCKS reply codes
        ERROR_MESSAGES.put(ProtocolError.INVALID_SOCKS_VERSION.getCode(), "invalid SOCKS version");
        ERROR_MESSAGES.put(ProtocolError.UNSUPPORTED_COMMAND.getCode(), "unsupported command");
        ERROR_MESSAGES.put(ProtocolError.HOST_UNREACHABLE.getCode(), "host unreachable");
        ERROR_MESSAGES.put(ProtocolError.CONNECTION_REFUSED.getCode(), "connection refused");
        ERROR_MESSAGES.put(ProtocolError.NETWORK_UNREACHABLE.getCode(), "network unreachable");
        ERROR_MESSAGES.put(ProtocolError.ADDRESS_NOT_SUPPORTED.getCode(), "address type not supported");
        ERROR_MESSAGES.put(ProtocolError.TTL_EXPIRED.getCode(), "TTL expired");
        ERROR_MESSAGES.put(ProtocolError.GENERAL_SOCKS_FAILURE.getCode(), "general SOCKS server failure");
        ERROR_MESSAGES.put(ProtocolError.AUTH_FAILED.getCode(), "authentication failed");

        // Protocol packet errors
        ERROR_MESSAGES.put(ProtocolError.INVALID_PACKET.getCode(), "invalid protocol packet structure");
        ERROR_MESSAGES.put(ProtocolError.INVALID_CRYPTO.getCode(), "invalid cryptographic operation");
    }

    public static String getMessage(byte errorCode) {
        return ERROR_MESSAGES.getOrDefault(errorCode, "unknown error");
    }
}
