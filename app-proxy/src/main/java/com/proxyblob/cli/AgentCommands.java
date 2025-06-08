package com.proxyblob.cli;

import com.azure.storage.blob.BlobContainerClient;
import com.proxyblob.ApplicationContext;
import com.proxyblob.proxy.server.ProxyServer;
import com.proxyblob.storage.ContainerInfo;
import com.proxyblob.util.ProxyFactory;
import com.proxyblob.util.TableRenderer;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

@Slf4j
@Command(name = "", description = "SOCKS Proxy over Azure Blob", subcommands = {
        AgentCommands.Create.class,
        AgentCommands.ListAgents.class,
        AgentCommands.Use.class,
        AgentCommands.Delete.class,
        AgentCommands.Start.class,
        AgentCommands.Stop.class,
})
public class AgentCommands {

    private final ApplicationContext context;

    public AgentCommands(ApplicationContext context) {
        this.context = context;

        registerSubcommand(new Create());
        registerSubcommand(new ListAgents());
        registerSubcommand(new Use());
        registerSubcommand(new Delete());
        registerSubcommand(new Start());
        registerSubcommand(new Stop());
    }

    @Command(name = "create", description = "Create a new agent container")
    static class Create extends BaseAgentCommand {
        @Option(names = {"-d", "--duration"}, description = "Token duration (e.g. PT7D)", defaultValue = "PT168H")
        Duration duration;

        @Override
        public void run() {
            try {
                String sasUrl = context.getStorageManager().createAgentContainer(duration);
                String encoded = Base64.getUrlEncoder().encodeToString(sasUrl.getBytes());
                log.info("Agent created. Share this connection string with the agent:\n{}", encoded);
            } catch (Exception e) {
                log.error("Failed to create agent", e);
            }
        }
    }

    @Command(name = "list", description = "List all available agents")
    static class ListAgents extends BaseAgentCommand {
        @Override
        public void run() {
            List<ContainerInfo> containers = context.getStorageManager().listAgentContainers();
            if (containers.isEmpty()) {
                log.info("No agent containers found.");
                return;
            }

            log.info("Available agents:");
            TableRenderer.renderAgentTable(containers);
        }
    }

    @Command(name = "use", description = "Select an agent for further operations")
    static class Use extends BaseAgentCommand {
        @Parameters(paramLabel = "container-id", description = "Container ID to use")
        String containerId;

        @Override
        public void run() {
            if (!context.getStorageManager().validateAgent(containerId)) {
                log.error("Agent not found: {}", containerId);
                return;
            }

            context.setSelectedAgent(containerId);
            String agentInfo = context.getStorageManager().getAgentInfo(containerId);
            log.info("Agent selected: {}", agentInfo);
        }
    }

    @Command(name = "delete", description = "Delete an agent container")
    static class Delete extends BaseAgentCommand {
        @Parameters(paramLabel = "container-id", arity = "1..*", description = "Container IDs to delete")
        List<String> ids;

        @Override
        public void run() {
            for (String id : ids) {
                if (context.getRunningProxies().containsKey(id)) {
                    log.warn("Stopping running proxy for container: {}", id);
                    context.getRunningProxies().get(id).stop();
                    context.getRunningProxies().remove(id);
                }

                context.getStorageManager().deleteAgentContainer(id);
                if (id.equals(context.getSelectedAgent())) {
                    context.clearSelectedAgent();
                }

                log.info("Deleted container: {}", id);
            }
        }
    }

    @Command(name = "start", description = "Start SOCKS proxy for selected agent")
    static class Start extends BaseAgentCommand {
        @Option(names = {"-l", "--listen"}, description = "Listen address", defaultValue = "127.0.0.1:1080")
        String listen;

        @Override
        public void run() {
            String selectedAgent = context.getSelectedAgent();
            if (selectedAgent == null || selectedAgent.isBlank()) {
                log.warn("No agent selected. Use 'use <container-id>' first.");
                return;
            }

            if (context.getRunningProxies().containsKey(selectedAgent)) {
                log.warn("Proxy already running for agent: {}", selectedAgent);
                return;
            }

            BlobContainerClient container = context.getStorageManager().getContainer(selectedAgent);
            ProxyServer server = ProxyFactory.create(container);
            server.start(listen);
            context.getRunningProxies().put(selectedAgent, server);

            log.info("Proxy started on {} for agent: {}", listen, selectedAgent);
        }
    }

    @Command(name = "stop", description = "Stop running proxy for selected agent")
    static class Stop extends BaseAgentCommand {
        @Override
        public void run() {
            String selectedAgent = context.getSelectedAgent();
            if (selectedAgent == null || !context.getRunningProxies().containsKey(selectedAgent)) {
                log.warn("No running proxy found for selected agent.");
                return;
            }

            context.getRunningProxies().get(selectedAgent).stop();
            context.getRunningProxies().remove(selectedAgent);
            log.info("Stopped proxy for agent: {}", selectedAgent);
        }
    }

    private void registerSubcommand(Runnable cmd) {
        if (cmd instanceof BaseAgentCommand bac) {
            bac.setContext(context);
        }
    }
}
