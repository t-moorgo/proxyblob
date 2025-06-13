package com.proxyblob.util;

import com.proxyblob.dto.ContainerInfo;
import de.vandermeer.asciitable.AsciiTable;
import lombok.experimental.UtilityClass;

import java.time.format.DateTimeFormatter;
import java.util.List;

@UtilityClass
public class TableRenderer {

    private final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public String renderAgentTable(List<ContainerInfo> containers) {
        AsciiTable table = new AsciiTable();

        table.addRule();
        table.addRow("Container ID", "Agent Info", "Proxy Port", "First Seen", "Last Seen");
        table.addRule();

        for (ContainerInfo container : containers) {
            table.addRow(
                    container.getId(),
                    container.getAgentInfo(),
                    container.getProxyPort() != null ? container.getProxyPort() : "",
                    FORMATTER.format(container.getCreatedAt()),
                    FORMATTER.format(container.getLastActivity())
            );
            table.addRule();
        }

        return table.render();
    }
}
