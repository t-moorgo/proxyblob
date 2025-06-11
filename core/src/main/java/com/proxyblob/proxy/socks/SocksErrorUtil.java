package com.proxyblob.proxy.socks;

import com.proxyblob.protocol.BaseHandler;
import com.proxyblob.protocol.Connection;
import lombok.experimental.UtilityClass;

import static com.proxyblob.protocol.ProtocolError.ErrAddressNotSupported;
import static com.proxyblob.protocol.ProtocolError.ErrAuthFailed;
import static com.proxyblob.protocol.ProtocolError.ErrConnectionRefused;
import static com.proxyblob.protocol.ProtocolError.ErrHostUnreachable;
import static com.proxyblob.protocol.ProtocolError.ErrNetworkUnreachable;
import static com.proxyblob.protocol.ProtocolError.ErrNone;
import static com.proxyblob.protocol.ProtocolError.ErrTTLExpired;
import static com.proxyblob.protocol.ProtocolError.ErrUnsupportedCommand;
import static com.proxyblob.proxy.socks.SocksConstants.AddressTypeNotSupported;
import static com.proxyblob.proxy.socks.SocksConstants.CommandNotSupported;
import static com.proxyblob.proxy.socks.SocksConstants.ConnectionRefused;
import static com.proxyblob.proxy.socks.SocksConstants.GeneralFailure;
import static com.proxyblob.proxy.socks.SocksConstants.HostUnreachable;
import static com.proxyblob.proxy.socks.SocksConstants.IPv4;
import static com.proxyblob.proxy.socks.SocksConstants.NetworkUnreachable;
import static com.proxyblob.proxy.socks.SocksConstants.NoAcceptableMethods;
import static com.proxyblob.proxy.socks.SocksConstants.Succeeded;
import static com.proxyblob.proxy.socks.SocksConstants.TTLExpired;
import static com.proxyblob.proxy.socks.SocksConstants.Version5;

@UtilityClass
public class SocksErrorUtil {

    public static void sendError(BaseHandler baseHandler, Connection conn, byte errCode) {
        byte socksReplyCode = GeneralFailure;

        // Map internal protocol errors to SOCKS5 reply codes
        switch (errCode) {
            case ErrNone -> socksReplyCode = Succeeded;
            case ErrNetworkUnreachable -> socksReplyCode = NetworkUnreachable;
            case ErrHostUnreachable -> socksReplyCode = HostUnreachable;
            case ErrConnectionRefused -> socksReplyCode = ConnectionRefused;
            case ErrTTLExpired -> socksReplyCode = TTLExpired;
            case ErrUnsupportedCommand -> socksReplyCode = CommandNotSupported;
            case ErrAddressNotSupported -> socksReplyCode = AddressTypeNotSupported;
            case ErrAuthFailed -> socksReplyCode = NoAcceptableMethods;
        }

        // 10-byte response: VER, REP, RSV, ATYP, BND.ADDR (IPv4), BND.PORT
        byte[] response = new byte[]{
                Version5,
                socksReplyCode,
                0x00,
                IPv4,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00
        };

        baseHandler.sendData(conn.getId(), response);
    }
}
