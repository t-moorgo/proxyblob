package com.proxyblob.util;

import lombok.experimental.UtilityClass;

@UtilityClass
public class Constants {

    public final String InfoBlobName = "info";
    public final String RequestBlobName = "request";
    public final String ResponseBlobName = "response";
    public final byte[] InfoKey = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xB1, 0x0B};
    public final String Banner = """
            
              ____                      ____  _       _     
             |  _ \\ _ __ _____  ___   _| __ )| | ___ | |__  
             | |_) | '__/ _ \\ \\/ / | | |  _ \\| |/ _ \\| '_ \\ 
             |  __/| | | (_) >  <| |_| | |_) | | (_) | |_) |
             |_|   |_|  \\___/_/\\_\\\\__, |____/|_|\\___/|_.__/ 
                                  |___/                     
            
               SOCKS Proxy over Azure Blob Storage (v1.0)
               ------------------------------------------
            """;
}
