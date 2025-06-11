package com.proxyblob.cli;

import com.proxyblob.state.AppState;
import com.proxyblob.storage.StorageManager;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;

public class ReplRunner {

    public static void run(StorageManager storageManager) throws IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new StringsCompleter("create", "list", "delete", "select", "start", "stop", "exit"))
                .history(new DefaultHistory())
                .variable(LineReader.HISTORY_FILE, new File(System.getProperty("user.home"), ".proxyblob_history").toPath())
                .build();

        CommandLine cli = CliInitializer.getCommandLine(storageManager);

        while (true) {
            String prompt = AppState.getCliPrompt() + " ";
            String line;

            try {
                line = reader.readLine(prompt);
            } catch (UserInterruptException | EndOfFileException e) {
                break;
            }

            if (line.trim().isEmpty()) continue;
            if (line.trim().equalsIgnoreCase("exit")) break;

            String[] args = line.trim().split("\\s+");
            cli.execute(args);
        }
    }
}
