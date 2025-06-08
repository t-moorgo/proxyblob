package com.proxyblob;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.proxyblob.cli.AgentCommands;
import com.proxyblob.config.Config;
import com.proxyblob.config.ConfigLoader;
import com.proxyblob.storage.StorageManager;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;

@Slf4j
public class MainProxy {
    public static void main(String[] args) {
        try {
            Config config = ConfigLoader.load("./config.json");

            StorageSharedKeyCredential credential = new StorageSharedKeyCredential(
                    config.getStorageAccountName(),
                    config.getStorageAccountKey()
            );

            String endpoint = config.getStorageUrl() != null && !config.getStorageUrl().isBlank()
                    ? config.getStorageUrl()
                    : "https://" + config.getStorageAccountName() + ".blob.core.windows.net";

            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .endpoint(endpoint)
                    .credential(credential)
                    .buildClient();

            StorageManager manager = new StorageManager(blobServiceClient);
            ApplicationContext appContext = new ApplicationContext(config, manager);

            printBanner();

            AgentCommands rootCommand = new AgentCommands(appContext);
            CommandLine cmd = new CommandLine(rootCommand);
            cmd.setCaseInsensitiveEnumValuesAllowed(true);
            cmd.setExecutionStrategy(new CommandLine.RunLast());
            cmd.execute(args);

        } catch (Exception e) {
            log.error("Fatal error: {}", e.getMessage(), e);
        }
    }

    private static void printBanner() {
        String banner = """
                
          ____                      ____  _       _     
         |  _ \\ _ __ _____  ___   _| __ )| | ___ | |__  
         | |_) | '__/ _ \\ \\/ / | | |  _ \\| |/ _ \\| '_ \\ 
         |  __/| | | (_) >  <| |_| | |_) | | (_) | |_) |
         |_|   |_|  \\___/_/\\_\\\\__, |____/|_|\\___/|_.__/ 
                              |___/                     

           SOCKS Proxy over Azure Blob Storage (v1.0)
           ------------------------------------------
        """;
        System.out.println(banner);
    }
}
