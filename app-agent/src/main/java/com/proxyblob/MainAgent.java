package com.proxyblob;

import java.util.Scanner;

public class MainAgent {

    public static void main(String[] args) {
        String connString = parseArgs(args);

        try {
            Agent agent = new Agent(connString);
            Runtime.getRuntime().addShutdownHook(new Thread(agent::stop));
            agent.start();

            new Scanner(System.in).nextLine(); // block main thread

        } catch (Exception e) {
            System.err.println("Agent startup failed: " + e.getMessage());
            System.exit(3);
        }
    }

    private static String parseArgs(String[] args) {
        if (args.length >= 2 && (args[0].equals("-c") || args[0].equals("--conn"))) {
            return args[1];
        }

        System.err.println("Missing connection string. Use -c <base64-encoded-URL>");
        System.exit(2);
        return null; // unreachable, for compiler
    }
}
