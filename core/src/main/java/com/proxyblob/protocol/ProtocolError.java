package com.proxyblob.protocol;

import com.proxyblob.transport.Transport;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ProtocolError {

    // General errors (0–9)
    public static final byte ErrNone = 0;
    public static final byte ErrInvalidCommand = 1;
    public static final byte ErrContextCanceled = 2;

    // Connection errors (10–19)
    public static final byte ErrConnectionClosed = 10;
    public static final byte ErrConnectionNotFound = 11;
    public static final byte ErrConnectionExists = 12;
    public static final byte ErrInvalidState = 13;
    public static final byte ErrPacketSendFailed = 14;
    public static final byte ErrHandlerStopped = 15;
    public static final byte ErrUnexpectedPacket = 16;

    // Transport errors (20–29)
    public static final byte ErrTransportClosed = Transport.ErrTransportClosed;
    public static final byte ErrTransportTimeout = Transport.ErrTransportTimeout;
    public static final byte ErrTransportError = Transport.ErrTransportError;

    // SOCKS errors (30–39)
    public static final byte ErrInvalidSocksVersion = 30;
    public static final byte ErrUnsupportedCommand = 31;
    public static final byte ErrHostUnreachable = 32;
    public static final byte ErrConnectionRefused = 33;
    public static final byte ErrNetworkUnreachable = 34;
    public static final byte ErrAddressNotSupported = 35;
    public static final byte ErrTTLExpired = 36;
    public static final byte ErrGeneralSocksFailure = 37;
    public static final byte ErrAuthFailed = 38;

    // Packet/Crypto errors (40–49)
    public static final byte ErrInvalidPacket = 40;
    public static final byte ErrInvalidCrypto = 41;

    // Thread/interruption
    public static final byte ErrThreadInterrupted = 42;
}
