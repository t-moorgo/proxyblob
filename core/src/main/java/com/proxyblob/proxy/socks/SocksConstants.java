package com.proxyblob.proxy.socks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class SocksConstants {
    public final byte Version5 = 0x05;
    public final byte NoAuth = 0x00;
    public final byte GSSAPI = 0x01;
    public final byte UsernamePassword = 0x02;
    public final byte NoAcceptableMethods = (byte) 0xFF;
    public final byte Connect = 0x01;
    public final byte Bind = 0x02;
    public final byte UDPAssociate = 0x03;
    public final byte IPv4 = 0x01;
    public final byte Domain = 0x03;
    public final byte IPv6 = 0x04;
    public final byte Succeeded = 0x00;
    public final byte GeneralFailure = 0x01;
    public final byte ConnectionNotAllowed = 0x02;
    public final byte NetworkUnreachable = 0x03;
    public final byte HostUnreachable = 0x04;
    public final byte ConnectionRefused = 0x05;
    public final byte TTLExpired = 0x06;
    public final byte CommandNotSupported = 0x07;
    public final byte AddressTypeNotSupported = 0x08;
    public final int MaxSocksHeaderSize = 262;
    public final int MaxUDPPacketSize = 65535;
}
