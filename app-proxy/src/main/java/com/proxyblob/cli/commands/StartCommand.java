package com.proxyblob.cli.commands;

import com.azure.storage.blob.BlobContainerClient;
import com.proxyblob.proxy.server.ProxyServer;
import com.proxyblob.state.AppState;
import com.proxyblob.storage.StorageManager;
import com.proxyblob.transport.BlobTransport;
import com.proxyblob.util.Constants;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;

@CommandLine.Command(
        name = "start",
        aliases = {"proxy"},
        description = "Start SOCKS proxy server"
)
@RequiredArgsConstructor
public class StartCommand implements Runnable {

    @CommandLine.Option(
            names = {"-l", "--listen"},
            description = "Listen address for SOCKS server",
            defaultValue = "127.0.0.1:1080"
    )
    private String listenAddress;

    private final StorageManager storageManager;

    @Override
    public void run() {
        String containerId = AppState.getSelectedAgent();
        if (containerId == null || containerId.isBlank()) {
            System.out.println("⚠️ No agent selected. Use 'select <container-id>' first.");
            return;
        }

        if (AppState.isProxyRunning(containerId)) {
            System.out.println("⚠️ Proxy already running for this agent.");
            return;
        }

        try {
            storageManager.validateAgent(containerId);

            BlobContainerClient containerClient = storageManager
                    .getServiceClient()
                    .getBlobContainerClient(containerId);

            BlobTransport transport = new BlobTransport(
                    containerClient.getBlobClient(Constants.ResponseBlobName).getBlockBlobClient(),
                    containerClient.getBlobClient(Constants.RequestBlobName).getBlockBlobClient(),
                    AppState.getContext()
            );

            ProxyServer proxyServer = new ProxyServer(transport, AppState.getContext());
            AppState.registerProxy(containerId, proxyServer);

            proxyServer.start(listenAddress);

            if (proxyServer.getListener() != null) {
                int port = proxyServer.getListener().getLocalPort();

                String agentInfo;
                try {
                    agentInfo = storageManager.getSelectedAgentInfo(containerId);
                } catch (Exception e) {
                    agentInfo = containerId;
                }

                System.out.printf("🚀 Proxy started for agent: %s on port: %d%n", agentInfo, port);
            }

        } catch (Exception e) {
            System.err.printf("❌ Cannot start proxy: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }
}
