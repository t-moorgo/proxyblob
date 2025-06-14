package com.proxyblob.cli.commands;

import com.proxyblob.dto.ContainerInfo;
import com.proxyblob.storage.StorageManager;
import com.proxyblob.util.TableRenderer;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;

import java.util.List;

@Command(
        name = "list",
        aliases = {"ls"},
        description = "List all existing agent containers"
)
@RequiredArgsConstructor
public class ListCommand implements Runnable {

    private final StorageManager storageManager;

    @Override
    public void run() {
        try {
            List<ContainerInfo> containers = storageManager.listAgentContainers();

            if (containers.isEmpty()) {
                System.out.println("📦 No agent containers found.");
                return;
            }

            String table = TableRenderer.renderAgentTable(containers);
            System.out.println(table);

        } catch (Exception e) {
            System.err.println("❌ Failed to list containers.");
            e.printStackTrace();
        }
    }
}
