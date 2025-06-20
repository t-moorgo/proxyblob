package com.proxyblob.cli.commands;

import com.azure.storage.blob.BlobContainerClient;
import com.proxyblob.proxy.server.ProxyServer;
import com.proxyblob.state.AppState;
import com.proxyblob.storage.StorageManager;
import com.proxyblob.transport.BlobTransport;
import com.proxyblob.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;

@Slf4j
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
            log.warn("No agent selected. Use 'select <container-id>' first.");
            return;
        }

        if (AppState.isProxyRunning(containerId)) {
            log.warn("Proxy already running for this agent.");
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

                log.info("Proxy started for agent: {} on port: {}", agentInfo, port);
            }

        } catch (Exception e) {
            log.error("Cannot start proxy: {}", e.getMessage(), e);
        }
    }
}
