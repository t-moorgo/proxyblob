package com.proxyblob.cli;

import com.proxyblob.storage.StorageManager;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;

@Command(
        name = "proxyblob",
        mixinStandardHelpOptions = true,
        version = "proxyblob 1.0",
        description = "CLI tool to manage proxy agents over Azure Blob Storage"
)
@RequiredArgsConstructor
public class ProxyBlobCli implements Runnable {

    private final StorageManager storageManager;

    @Override
    public void run() {
        System.out.println("Use --help to see available commands.");
    }
}
