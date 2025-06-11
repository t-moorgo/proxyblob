package com.proxyblob.constants;

public class Constants {

    public static final String InfoBlobName = "info";     // agent metadata
    public static final String RequestBlobName = "request"; // proxy-to-agent traffic
    public static final String ResponseBlobName = "response"; // agent-to-proxy traffic
    public static final byte[] InfoKey = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xB1, 0x0B};
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
