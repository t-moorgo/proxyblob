package com.proxyblob.proxy.socks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class SocksConstants {
    public static final byte VERSION_5 = 0x05;

    // Auth methods
    public static final byte NO_AUTH = 0x00;
    public static final byte NO_ACCEPTABLE_METHODS = (byte) 0xFF;

    // Commands
    public static final byte CONNECT = 0x01;
    public static final byte BIND = 0x02;
    public static final byte UDP_ASSOCIATE = 0x03;

    // Address types
    public static final byte IPV4 = 0x01;
    public static final byte DOMAIN = 0x03;
    public static final byte IPV6 = 0x04;

    // Reply codes
    public static final byte SUCCEEDED = 0x00;
    public static final byte GENERAL_FAILURE = 0x01;
    public static final byte CONNECTION_NOT_ALLOWED = 0x02;
    public static final byte NETWORK_UNREACHABLE = 0x03;
    public static final byte HOST_UNREACHABLE = 0x04;
    public static final byte CONNECTION_REFUSED = 0x05;
    public static final byte TTL_EXPIRED = 0x06;
    public static final byte COMMAND_NOT_SUPPORTED = 0x07;
    public static final byte ADDRESS_TYPE_NOT_SUPPORTED = 0x08;
}
