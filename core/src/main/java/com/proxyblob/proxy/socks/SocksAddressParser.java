package com.proxyblob.proxy.socks;

import com.proxyblob.proxy.socks.dto.ParsedAddress;
import lombok.experimental.UtilityClass;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static com.proxyblob.protocol.ProtocolError.ErrAddressNotSupported;
import static com.proxyblob.protocol.ProtocolError.ErrInvalidPacket;
import static com.proxyblob.protocol.ProtocolError.ErrNone;
import static com.proxyblob.proxy.socks.SocksConstants.Domain;
import static com.proxyblob.proxy.socks.SocksConstants.IPv4;
import static com.proxyblob.proxy.socks.SocksConstants.IPv6;

@UtilityClass
public class SocksAddressParser {

    public static ParsedAddress parseAddress(byte[] data) {
        if (data == null || data.length < 2) {
            return new ParsedAddress(null, 0, ErrInvalidPacket);
        }

        byte addrType = data[0];
        byte[] addressData = Arrays.copyOfRange(data, 1, data.length);

        ParsedAddress parsed = parseNetworkAddress(addrType, addressData);
        if (parsed.getErrorCode() != ErrNone) {
            return parsed;
        }

        return new ParsedAddress(parsed.getHostAndPort(), parsed.getConsumedBytes() + 1, ErrNone);
    }

    public static ParsedAddress extractUDPHeader(byte[] data) {
        int headerLen = 4; // RSV(2) + FRAG(1) + ATYP(1)

        if (data == null || data.length < 5) {
            return new ParsedAddress(null, 0, ErrInvalidPacket);
        }

        byte addrType = data[3];
        byte[] addressData = Arrays.copyOfRange(data, 4, data.length);

        ParsedAddress parsed = parseNetworkAddress(addrType, addressData);
        if (parsed.getErrorCode() != ErrNone) {
            return new ParsedAddress(null, 0, parsed.getErrorCode());
        }

        return new ParsedAddress(parsed.getHostAndPort(), headerLen + parsed.getConsumedBytes(), ErrNone);
    }

    private static ParsedAddress parseNetworkAddress(byte addrType, byte[] data) {
        int cursor = 0;
        String addr;

        try {
            switch (addrType) {
                case IPv4 -> {
                    if (data.length < cursor + 4 + 2) {
                        return error();
                    }
                    byte[] ipBytes = Arrays.copyOfRange(data, cursor, cursor + 4);
                    addr = InetAddress.getByAddress(ipBytes).getHostAddress();
                    cursor += 4;
                }
                case IPv6 -> {
                    if (data.length < cursor + 16 + 2) {
                        return error();
                    }
                    byte[] ipBytes = Arrays.copyOfRange(data, cursor, cursor + 16);
                    addr = "[" + InetAddress.getByAddress(ipBytes).getHostAddress() + "]";
                    cursor += 16;
                }
                case Domain -> {
                    if (data.length < cursor + 1) {
                        return error();
                    }
                    int domainLen = data[cursor++] & 0xFF;
                    if (data.length < cursor + domainLen + 2) {
                        return error();
                    }
                    addr = new String(Arrays.copyOfRange(data, cursor, cursor + domainLen));
                    cursor += domainLen;
                }
                default -> {
                    return error();
                }
            }

            if (data.length < cursor + 2) {
                return error();
            }

            int port = ByteBuffer.wrap(data, cursor, 2).getShort() & 0xFFFF;
            cursor += 2;

            return new ParsedAddress(addr + ":" + port, cursor, ErrNone);

        } catch (UnknownHostException e) {
            return error();
        }
    }

    private static ParsedAddress error() {
        return new ParsedAddress(null, 0, ErrAddressNotSupported);
    }
}
