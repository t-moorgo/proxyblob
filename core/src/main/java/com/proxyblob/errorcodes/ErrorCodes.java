package com.proxyblob.errorcodes;

import lombok.experimental.UtilityClass;

@UtilityClass
public class ErrorCodes {
    public final byte ErrNone = 0;
    public final byte ErrInvalidCommand = 1;
    public final byte ErrContextCanceled = 2;
    public final byte ErrConnectionClosed = 10;
    public final byte ErrConnectionNotFound = 11;
    public final byte ErrConnectionExists = 12;
    public final byte ErrInvalidState = 13;
    public final byte ErrPacketSendFailed = 14;
    public final byte ErrHandlerStopped = 15;
    public final byte ErrUnexpectedPacket = 16;
    public final byte ErrTransportClosed = 20;
    public final byte ErrTransportTimeout = 21;
    public final byte ErrTransportError = 22;
    public final byte ErrInvalidSocksVersion = 30;
    public final byte ErrUnsupportedCommand = 31;
    public final byte ErrHostUnreachable = 32;
    public final byte ErrConnectionRefused = 33;
    public final byte ErrNetworkUnreachable = 34;
    public final byte ErrAddressNotSupported = 35;
    public final byte ErrTTLExpired = 36;
    public final byte ErrGeneralSocksFailure = 37;
    public final byte ErrAuthFailed = 38;
    public final byte ErrInvalidPacket = 40;
    public final byte ErrInvalidCrypto = 41;
}
