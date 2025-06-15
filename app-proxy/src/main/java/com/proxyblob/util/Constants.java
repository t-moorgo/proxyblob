package com.proxyblob.util;

public class Constants {

    public static final String InfoBlobName = "info";
    public static final String RequestBlobName = "request";
    public static final String ResponseBlobName = "response";
    public static final byte[] InfoKey = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xB1, 0x0B};
    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_YELLOW_BOLD = "\u001B[1;33m";
    public static final String Banner = """
            
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
