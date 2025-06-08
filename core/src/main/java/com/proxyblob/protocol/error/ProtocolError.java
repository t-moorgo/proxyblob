package com.proxyblob.protocol.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ProtocolError {
    // General (0–9)
    NONE((byte) 0),
    INVALID_COMMAND((byte) 1),
    CONTEXT_CANCELED((byte) 2),
    INVALID_REQUEST((byte) 3),

    // Connection (10–19)
    CONNECTION_CLOSED((byte) 10),
    CONNECTION_NOT_FOUND((byte) 11),
    CONNECTION_EXISTS((byte) 12),
    INVALID_STATE((byte) 13),
    PACKET_SEND_FAILED((byte) 14),
    HANDLER_STOPPED((byte) 15),
    UNEXPECTED_PACKET((byte) 16),
    UNREACHABLE((byte) 17),

    // Transport (20–29)
    TRANSPORT_CLOSED((byte) 20),
    TRANSPORT_TIMEOUT((byte) 21),
    TRANSPORT_ERROR((byte) 22),

    // SOCKS (30–39)
    INVALID_SOCKS_VERSION((byte) 30),
    UNSUPPORTED_COMMAND((byte) 31),
    HOST_UNREACHABLE((byte) 32),
    CONNECTION_REFUSED((byte) 33),
    NETWORK_UNREACHABLE((byte) 34),
    ADDRESS_NOT_SUPPORTED((byte) 35),
    TTL_EXPIRED((byte) 36),
    GENERAL_SOCKS_FAILURE((byte) 37),
    AUTH_FAILED((byte) 38),

    // Packet/Crypto (40–49)
    INVALID_PACKET((byte) 40),
    INVALID_CRYPTO((byte) 41);

    private final byte code;

    public static ProtocolError fromByte(byte code) {
        for (ProtocolError e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }
}
