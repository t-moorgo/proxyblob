package com.proxyblob.constants;

import lombok.experimental.UtilityClass;

import java.util.concurrent.atomic.AtomicReference;

@UtilityClass
public class Constants {
    public static final int Success = 0;
    public static final int ErrContextCanceled = 1;
    public static final int ErrNoConnectionString = 2;
    public static final int ErrConnectionStringError = 3;
    public static final int ErrInfoBlobError = 4;
    public static final int ErrContainerNotFound = 5;
    public static final String InfoBlobName = "info";
    public static final String RequestBlobName = "request";
    public static final String ResponseBlobName = "response";
    public static final byte[] InfoKey = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xB1, 0x0B};
    public static final AtomicReference<String> connStringRef = new AtomicReference<>("");
}
