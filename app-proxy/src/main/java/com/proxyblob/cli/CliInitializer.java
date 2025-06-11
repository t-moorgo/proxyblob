package com.proxyblob.cli;

import com.proxyblob.state.AppState;
import com.proxyblob.config.Config;
import com.proxyblob.storage.StorageManager;
import com.proxyblob.cli.commands.CreateCommand;
import com.proxyblob.cli.commands.DeleteCommand;
import com.proxyblob.cli.commands.ListCommand;
import com.proxyblob.cli.commands.SelectCommand;
import com.proxyblob.cli.commands.StartCommand;
import com.proxyblob.cli.commands.StopCommand;
import com.proxyblob.context.AppContext;
import picocli.CommandLine;

import static com.proxyblob.constants.Constants.Banner;

public class CliInitializer {

    public static void setupCLI(String[] args) {
        AppContext context = new AppContext();
        AppState.setContext(context);

        String configPath = extractConfigPath(args);
        Config config = Config.load(configPath);
        config.validate();
        AppState.setConfig(config);

        StorageManager storageManager = new StorageManager(config);
        AppState.setStorageManager(storageManager);

        printBanner();
    }

    public static CommandLine getCommandLine(StorageManager storageManager) {
        CommandLine cli = new CommandLine(new ProxyBlobCli(storageManager));

        cli.addSubcommand("create", new CreateCommand(storageManager));
        cli.addSubcommand("list", new ListCommand(storageManager));
        cli.addSubcommand("delete", new DeleteCommand(storageManager));
        cli.addSubcommand("select", new SelectCommand(storageManager));
        cli.addSubcommand("start", new StartCommand(storageManager));
        cli.addSubcommand("stop", new StopCommand(storageManager));

        return cli;
    }

    public static String extractConfigPath(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--config") || args[i].equals("-c")) {
                return args[i + 1];
            }
        }
        return "config.json"; // значение по умолчанию
    }

    private static void printBanner() {
        System.out.println(Banner);
    }
}
