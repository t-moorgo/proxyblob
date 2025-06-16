package com.proxyblob.cli.commands;

import com.proxyblob.cli.AgentIdCandidates;
import com.proxyblob.state.AppState;
import com.proxyblob.storage.StorageManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;

import java.util.List;
import java.util.Scanner;

@Slf4j
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
                log.warn("No container ID provided and no agent selected.");
                return;
            }
        }

        for (String containerId : containerIds) {
            log.info("Are you sure you want to delete container '{}'? [y/N]: ", containerId);
            System.out.print("> ");

            Scanner scanner = new Scanner(System.in);
            String input = scanner.nextLine().trim();

            if (!input.equalsIgnoreCase("y")) {
                log.info("Deletion cancelled for container: {}", containerId);
                continue;
            }

            try {
                storageManager.deleteAgentContainer(containerId);

                if (containerId.equals(AppState.getSelectedAgent())) {
                    AppState.setSelectedAgent(null);
                }

                log.info("Container deleted successfully: {}", containerId);
            } catch (Exception e) {
                log.error("Failed to delete container: {}", containerId, e);
            }
        }
    }
}
