package com.proxyblob.cli.commands;

import com.proxyblob.proxy.server.ProxyServer;
import com.proxyblob.state.AppState;
import com.proxyblob.storage.StorageManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;

@Slf4j
@CommandLine.Command(
        name = "stop",
        description = "Stop running proxy for the selected agent"
)
@RequiredArgsConstructor
public class StopCommand implements Runnable {

    private final StorageManager storageManager;

    @Override
    public void run() {
        String containerId = AppState.getSelectedAgent();
        if (containerId == null || containerId.isBlank()) {
            log.warn("No agent selected. Use 'select <container-id>' first.");
            return;
        }

        ProxyServer server = AppState.getProxy(containerId);
        if (server == null) {
            log.warn("No proxy is currently running for this agent.");
            return;
        }

        server.stop();
        AppState.removeProxy(containerId);

        String agentInfo;
        try {
            agentInfo = storageManager.getSelectedAgentInfo(containerId);
        } catch (Exception e) {
            agentInfo = containerId;
        }

        log.info("Proxy stopped for agent: {}", agentInfo);
    }
}
