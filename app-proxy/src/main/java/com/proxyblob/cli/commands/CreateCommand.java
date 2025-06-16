package com.proxyblob.cli.commands;

import com.proxyblob.dto.ContainerCreationResult;
import com.proxyblob.storage.StorageManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Slf4j
@Command(
        name = "create",
        aliases = {"new"},
        description = "Create a new agent container and generate its connection string"
)
@RequiredArgsConstructor
public class CreateCommand implements Runnable {

    private final StorageManager storageManager;

    @Option(
            names = {"-d", "--duration"},
            description = "Duration for the SAS token (e.g. PT168H for 7 days). Default is 7 days.",
            defaultValue = "PT168H"
    )
    private Duration duration;

    @Override
    public void run() {
        try {
            ContainerCreationResult result = storageManager.createAgentContainer(duration);
            String encodedConnStr = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(result.getConnectionString().getBytes(StandardCharsets.UTF_8));

            log.info("Agent container created successfully: {}", result.getContainerId());
            log.info("Connection string: {}", encodedConnStr);
        } catch (Exception e) {
            log.error("Failed to create agent container", e);
        }
    }
}
