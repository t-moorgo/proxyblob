package com.proxyblob.proxy.socks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class SocksConstants {
    public static final byte Version5 = 0x05;
    public static final byte NoAuth = 0x00;
    public static final byte GSSAPI = 0x01;
    public static final byte UsernamePassword = 0x02;
    public static final byte NoAcceptableMethods = (byte) 0xFF;
    public static final byte Connect = 0x01;
    public static final byte Bind = 0x02;
    public static final byte UDPAssociate = 0x03;
    public static final byte IPv4 = 0x01;
    public static final byte Domain = 0x03;
    public static final byte IPv6 = 0x04;
    public static final byte Succeeded = 0x00;
    public static final byte GeneralFailure = 0x01;
    public static final byte ConnectionNotAllowed = 0x02;
    public static final byte NetworkUnreachable = 0x03;
    public static final byte HostUnreachable = 0x04;
    public static final byte ConnectionRefused = 0x05;
    public static final byte TTLExpired = 0x06;
    public static final byte CommandNotSupported = 0x07;
    public static final byte AddressTypeNotSupported = 0x08;
    public static final int MaxSocksHeaderSize = 262;
    public static final int MaxUDPPacketSize = 65535;
}
