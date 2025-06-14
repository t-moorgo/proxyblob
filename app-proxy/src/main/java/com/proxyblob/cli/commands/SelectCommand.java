package com.proxyblob.cli.commands;

import com.proxyblob.cli.AgentIdCandidates;
import com.proxyblob.state.AppState;
import com.proxyblob.storage.StorageManager;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;

@CommandLine.Command(
        name = "select",
        aliases = {"use"},
        description = "Select an agent for subsequent commands"
)
@RequiredArgsConstructor
public class SelectCommand implements Runnable {

    @CommandLine.Parameters(
            index = "0",
            description = "ID of the container to select",
            completionCandidates = AgentIdCandidates.class
    )
    private String containerId;

    private final StorageManager storageManager;

    @Override
    public void run() {
        try {
            storageManager.validateAgent(containerId);

            AppState.setSelectedAgent(containerId);

            String agentInfo = storageManager.getSelectedAgentInfo(containerId);
            if (agentInfo == null || agentInfo.isBlank()) {
                agentInfo = "unknown@host";
            }

            System.out.println("✅ Agent selected: " + agentInfo);
            AppState.setCliPrompt(agentInfo + " »");

        } catch (Exception e) {
            System.err.println("❌ Failed to select agent: " + containerId);
            e.printStackTrace();
        }
    }
}
