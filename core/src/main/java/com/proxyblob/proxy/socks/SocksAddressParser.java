package com.proxyblob.proxy.socks;

import com.proxyblob.protocol.error.ProtocolError;
import lombok.experimental.UtilityClass;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;

@UtilityClass
public class SocksAddressParser {

    public static Result parse(byte[] data, int offset) {
        if (data.length <= offset) {
            return new Result(null, 0, ProtocolError.ADDRESS_NOT_SUPPORTED.getCode());
        }
        byte atyp = data[offset];
        return parseNetworkAddress(atyp, data, offset + 1, offset + 1);
    }

    public static Result parseUDPHeader(byte[] data) {
        if (data.length < 4) {
            return new Result(null, 0, ProtocolError.INVALID_PACKET.getCode());
        }
        byte atyp = data[3];
        return parseNetworkAddress(atyp, data, 4, 4);
    }

    private static Result parseNetworkAddress(byte atyp, byte[] data, int cursor, int baseOffset) {
        String address;

        try {
            switch (atyp) {
                case SocksConstants.IPV4 -> {
                    if (data.length < cursor + 4 + 2) return error();
                    byte[] ipBytes = Arrays.copyOfRange(data, cursor, cursor + 4);
                    address = InetAddress.getByAddress(ipBytes).getHostAddress();
                    cursor += 4;
                }
                case SocksConstants.IPV6 -> {
                    if (data.length < cursor + 16 + 2) return error();
                    byte[] ipBytes = Arrays.copyOfRange(data, cursor, cursor + 16);
                    address = "[" + InetAddress.getByAddress(ipBytes).getHostAddress() + "]";
                    cursor += 16;
                }
                case SocksConstants.DOMAIN -> {
                    if (data.length < cursor + 1) return error();
                    int length = data[cursor++] & 0xFF;
                    if (data.length < cursor + length + 2) return error();
                    address = new String(Arrays.copyOfRange(data, cursor, cursor + length));
                    cursor += length;
                }
                default -> {
                    return error();
                }
            }

            if (data.length < cursor + 2) return error();
            int port = ByteBuffer.wrap(data, cursor, 2).getShort() & 0xFFFF;
            cursor += 2;

            return new Result(address + ":" + port, cursor - baseOffset, ProtocolError.NONE.getCode());

        } catch (UnknownHostException e) {
            return error();
        }
    }

    private static Result error() {
        return new Result(null, 0, ProtocolError.ADDRESS_NOT_SUPPORTED.getCode());
    }

    public record Result(String hostAndPort, int consumedBytes, byte errorCode) {
    }
}
