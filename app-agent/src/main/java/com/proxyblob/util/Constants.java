package com.proxyblob.util;

import lombok.experimental.UtilityClass;

import java.util.concurrent.atomic.AtomicReference;

@UtilityClass
public class Constants {
    public final int Success = 0;
    public final int ErrContextCanceled = 1;
    public final int ErrNoConnectionString = 2;
    public final int ErrConnectionStringError = 3;
    public final int ErrInfoBlobError = 4;
    public final int ErrContainerNotFound = 5;
    public final String InfoBlobName = "info";
    public final String RequestBlobName = "request";
    public final String ResponseBlobName = "response";
    public final byte[] InfoKey = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xB1, 0x0B};
    public final AtomicReference<String> connStringRef = new AtomicReference<>("");
}
