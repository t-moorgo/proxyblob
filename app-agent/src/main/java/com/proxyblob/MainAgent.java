package com.proxyblob;

import com.proxyblob.context.AppContext;
import com.proxyblob.dto.AgentCreationResult;

import static com.proxyblob.constants.Constants.ErrNoConnectionString;
import static com.proxyblob.constants.Constants.Success;
import static com.proxyblob.constants.Constants.connStringRef;

public class MainAgent {

    public static void main(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("-c".equals(args[i])) {
                connStringRef.set(args[i + 1]);
                break;
            }
        }

        String connString = connStringRef.get();
        if (connString == null || connString.isEmpty()) {
            System.exit(ErrNoConnectionString);
        }

        AppContext context = new AppContext();

        Runtime.getRuntime().addShutdownHook(new Thread(context::stop));

        Agent temp = new Agent(null, null);
        AgentCreationResult result = temp.create(context, connString);

        if (result.getStatus() != Success) {
            System.exit(result.getStatus());
        }

        int exitCode = result.getAgent().start(context);
        System.exit(exitCode);
    }
}
