package com.proxyblob.cli.commands;

import com.proxyblob.proxy.server.ProxyServer;
import com.proxyblob.state.AppState;
import com.proxyblob.storage.StorageManager;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;

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
            System.out.println("⚠️ No agent selected. Use 'select <container-id>' first.");
            return;
        }

        ProxyServer server = AppState.getProxy(containerId);
        if (server == null) {
            System.out.println("⚠️ No proxy is currently running for this agent.");
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

        System.out.printf("🛑 Proxy stopped for agent: %s%n", agentInfo);
    }
}
