package com.proxyblob.cli.commands;

import com.proxyblob.dto.ContainerInfo;
import com.proxyblob.storage.StorageManager;
import com.proxyblob.util.TableRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;

import java.util.List;

@Slf4j
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
                log.info("No agent containers found.");
                return;
            }

            String table = TableRenderer.renderAgentTable(containers);
            System.out.println(table);

        } catch (Exception e) {
            log.error("Failed to list containers.", e);
        }
    }
}
