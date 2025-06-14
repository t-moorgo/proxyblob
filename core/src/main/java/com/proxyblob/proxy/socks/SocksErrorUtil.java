package com.proxyblob.proxy.socks;

import com.proxyblob.protocol.BaseHandler;
import com.proxyblob.protocol.Connection;

import static com.proxyblob.errorcodes.ErrorCodes.ErrAddressNotSupported;
import static com.proxyblob.errorcodes.ErrorCodes.ErrAuthFailed;
import static com.proxyblob.errorcodes.ErrorCodes.ErrConnectionRefused;
import static com.proxyblob.errorcodes.ErrorCodes.ErrHostUnreachable;
import static com.proxyblob.errorcodes.ErrorCodes.ErrNetworkUnreachable;
import static com.proxyblob.errorcodes.ErrorCodes.ErrNone;
import static com.proxyblob.errorcodes.ErrorCodes.ErrTTLExpired;
import static com.proxyblob.errorcodes.ErrorCodes.ErrUnsupportedCommand;
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

public class SocksErrorUtil {

    public static void sendError(BaseHandler baseHandler, Connection conn, byte errCode) {
        byte socksReplyCode = GeneralFailure;

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
