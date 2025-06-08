package com.proxyblob.util;

import com.proxyblob.storage.ContainerInfo;
import lombok.experimental.UtilityClass;

import java.time.format.DateTimeFormatter;
import java.util.List;

@UtilityClass
public class TableRenderer {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void renderAgentTable(List<ContainerInfo> containers) {
        String format = "| %-36s | %-20s | %-10s | %-19s | %-19s |%n";

        String line = "+--------------------------------------+----------------------+------------+---------------------+---------------------+";
        System.out.println(line);
        System.out.printf(format, "Container ID", "Agent Info", "Port", "Created At", "Last Activity");
        System.out.println(line);

        for (ContainerInfo c : containers) {
            System.out.printf(
                    format,
                    c.getId(),
                    truncate(c.getAgentInfo(), 20),
                    (c.getProxyPort() == null || c.getProxyPort().isBlank()) ? "-" : c.getProxyPort(),
                    FORMATTER.format(c.getCreatedAt()),
                    FORMATTER.format(c.getLastActivity())
            );
        }

        System.out.println(line);
    }

    private static String truncate(String input, int max) {
        if (input == null) return "";
        return input.length() <= max ? input : input.substring(0, max - 3) + "...";
    }
}
