package com.proxyblob.proxy.socks;

import com.proxyblob.proxy.socks.dto.ParsedAddress;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static com.proxyblob.errorcodes.ErrorCodes.ErrAddressNotSupported;
import static com.proxyblob.errorcodes.ErrorCodes.ErrInvalidPacket;
import static com.proxyblob.errorcodes.ErrorCodes.ErrNone;
import static com.proxyblob.proxy.socks.SocksConstants.Domain;
import static com.proxyblob.proxy.socks.SocksConstants.IPv4;
import static com.proxyblob.proxy.socks.SocksConstants.IPv6;

public class SocksAddressParser {

    public static ParsedAddress parseAddress(byte[] data) {
        if (data == null || data.length < 2) {
            System.out.println("[SocksAddressParser] parseAddress: data too short or null");
            return ParsedAddress.builder()
                    .hostAndPort(null)
                    .consumedBytes(0)
                    .errorCode(ErrInvalidPacket)
                    .build();
        }

        byte addrType = data[0];
        byte[] addressData = Arrays.copyOfRange(data, 1, data.length);
        System.out.printf("[SocksAddressParser] parseAddress: addrType=0x%02X, dataLen=%d%n", addrType, data.length);

        ParsedAddress parsed = parseNetworkAddress(addrType, addressData);
        if (parsed.getErrorCode() != ErrNone) {
            System.out.println("[SocksAddressParser] parseAddress: failed to parse network address");
            return parsed;
        }

        System.out.printf("[SocksAddressParser] parseAddress: hostAndPort=%s, consumed=%d%n", parsed.getHostAndPort(), parsed.getConsumedBytes());

        return ParsedAddress.builder()
                .hostAndPort(parsed.getHostAndPort())
                .consumedBytes(parsed.getConsumedBytes() + 1)
                .errorCode(ErrNone)
                .build();
    }

    public static ParsedAddress extractUDPHeader(byte[] data) {
        int headerLen = 4;

        if (data == null || data.length < 5) {
            System.out.println("[SocksAddressParser] extractUDPHeader: data too short or null");
            return ParsedAddress.builder()
                    .hostAndPort(null)
                    .consumedBytes(0)
                    .errorCode(ErrInvalidPacket)
                    .build();
        }

        byte addrType = data[3];
        byte[] addressData = Arrays.copyOfRange(data, 4, data.length);
        System.out.printf("[SocksAddressParser] extractUDPHeader: addrType=0x%02X, totalDataLen=%d%n", addrType, data.length);

        ParsedAddress parsed = parseNetworkAddress(addrType, addressData);
        if (parsed.getErrorCode() != ErrNone) {
            System.out.println("[SocksAddressParser] extractUDPHeader: failed to parse network address");
            return ParsedAddress.builder()
                    .hostAndPort(null)
                    .consumedBytes(0)
                    .errorCode(parsed.getErrorCode())
                    .build();
        }

        System.out.printf("[SocksAddressParser] extractUDPHeader: hostAndPort=%s, consumed=%d%n", parsed.getHostAndPort(), parsed.getConsumedBytes());

        return ParsedAddress.builder()
                .hostAndPort(parsed.getHostAndPort())
                .consumedBytes(headerLen + parsed.getConsumedBytes())
                .errorCode(ErrNone)
                .build();
    }

    private static ParsedAddress parseNetworkAddress(byte addrType, byte[] data) {
        int cursor = 0;
        String addr;

        try {
            switch (addrType) {
                case IPv4 -> {
                    System.out.println("[SocksAddressParser] parseNetworkAddress: IPv4");
                    if (data.length < cursor + 4 + 2) {
                        return error("not enough data for IPv4");
                    }
                    byte[] ipBytes = Arrays.copyOfRange(data, cursor, cursor + 4);
                    addr = InetAddress.getByAddress(ipBytes).getHostAddress();
                    cursor += 4;
                }
                case IPv6 -> {
                    System.out.println("[SocksAddressParser] parseNetworkAddress: IPv6");
                    if (data.length < cursor + 16 + 2) {
                        return error("not enough data for IPv6");
                    }
                    byte[] ipBytes = Arrays.copyOfRange(data, cursor, cursor + 16);
                    addr = "[" + InetAddress.getByAddress(ipBytes).getHostAddress() + "]";
                    cursor += 16;
                }
                case Domain -> {
                    System.out.println("[SocksAddressParser] parseNetworkAddress: Domain");
                    if (data.length < cursor + 1) {
                        return error("not enough data for domain length byte");
                    }
                    int domainLen = data[cursor++] & 0xFF;
                    if (data.length < cursor + domainLen + 2) {
                        return error("not enough data for domain and port");
                    }
                    addr = new String(Arrays.copyOfRange(data, cursor, cursor + domainLen));
                    cursor += domainLen;
                }
                default -> {
                    return error("unsupported addrType: " + addrType);
                }
            }

            if (data.length < cursor + 2) {
                return error("missing port bytes");
            }

            int port = ByteBuffer.wrap(data, cursor, 2).getShort() & 0xFFFF;
            cursor += 2;
            String hostAndPort = addr + ":" + port;
            System.out.printf("[SocksAddressParser] parseNetworkAddress: Parsed %s%n", hostAndPort);

            return ParsedAddress.builder()
                    .hostAndPort(hostAndPort)
                    .consumedBytes(cursor)
                    .errorCode(ErrNone)
                    .build();
        } catch (UnknownHostException e) {
            return error("UnknownHostException: " + e.getMessage());
        }
    }

    private static ParsedAddress error(String reason) {
        System.out.println("[SocksAddressParser] ERROR: " + reason);
        return ParsedAddress.builder()
                .hostAndPort(null)
                .consumedBytes(0)
                .errorCode(ErrAddressNotSupported)
                .build();
    }
}
