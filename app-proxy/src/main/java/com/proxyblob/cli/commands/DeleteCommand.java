package com.proxyblob.cli.commands;

import com.proxyblob.cli.AgentIdCandidates;
import com.proxyblob.state.AppState;
import com.proxyblob.storage.StorageManager;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;

import java.util.List;
import java.util.Scanner;

@CommandLine.Command(
        name = "delete",
        aliases = {"rm"},
        description = "Delete an existing agent container"
)
@RequiredArgsConstructor
public class DeleteCommand implements Runnable {

    @CommandLine.Parameters(
            arity = "0..*",
            paramLabel = "containers-id",
            description = "IDs of the containers to delete",
            completionCandidates = AgentIdCandidates.class
    )
    private List<String> containerIds;

    private final StorageManager storageManager;

    @Override
    public void run() {
        if (containerIds == null || containerIds.isEmpty()) {
            String selectedAgent = AppState.getSelectedAgent();
            if (selectedAgent != null && !selectedAgent.isBlank()) {
                containerIds = List.of(selectedAgent);
            } else {
                System.out.println("⚠️ No container ID provided and no agent selected.");
                return;
            }
        }

        for (String containerId : containerIds) {
            System.out.printf("❓ Are you sure you want to delete container '%s'? [y/N]: ", containerId);

            Scanner scanner = new Scanner(System.in);
            String input = scanner.nextLine().trim();

            if (!input.equalsIgnoreCase("y")) {
                System.out.println("🚫 Deletion cancelled for container: " + containerId);
                continue;
            }

            try {
                storageManager.deleteAgentContainer(containerId);

                if (containerId.equals(AppState.getSelectedAgent())) {
                    AppState.setSelectedAgent(null);
                }

                System.out.println("✅ Container deleted successfully: " + containerId);
            } catch (Exception e) {
                System.err.println("❌ Failed to delete container: " + containerId);
                e.printStackTrace();
            }
        }
    }
}
