package com.proxyblob;

import com.proxyblob.cli.CliInitializer;
import com.proxyblob.cli.ReplRunner;
import com.proxyblob.state.AppState;
import picocli.CommandLine;

public class MainProxy {

    public static void main(String[] args) throws Exception {
        CliInitializer.setupCLI(args);

        if (args.length == 0) {
            ReplRunner.run(AppState.getStorageManager());
        } else {
            CommandLine cli = CliInitializer.getCommandLine(AppState.getStorageManager());
            int exitCode = cli.execute(args);
            System.exit(exitCode);
        }
    }
}
