package com.proxyblob.cli.commands;

import com.proxyblob.state.AppState;
import com.proxyblob.storage.StorageManager;
import com.proxyblob.cli.AgentIdCandidates;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

@CommandLine.Command(
        name = "select",
        aliases = {"use"},
        description = "Select an agent for subsequent commands"
)
@RequiredArgsConstructor
public class SelectCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(SelectCommand.class);

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
            // Проверка, существует ли агент
            storageManager.validateAgent(containerId);

            // Сохраняем выбранного агента
            AppState.setSelectedAgent(containerId);

            // Получаем информацию об агенте
            String agentInfo = storageManager.getSelectedAgentInfo(containerId);
            if (agentInfo == null || agentInfo.isBlank()) {
                agentInfo = "unknown@host";
            }

            log.info("Agent selected: {}", agentInfo);
            AppState.setCliPrompt(agentInfo + " »");

        } catch (Exception e) {
            log.error("Failed to select agent: {}", containerId, e);
        }
    }
}
