package com.proxyblob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Data
@AllArgsConstructor
public class ContainerInfo {
    private String id;            // container ID
    private String agentInfo;     // username@hostname
    private String proxyPort;     // SOCKS port
    private Instant createdAt;    // creation time
    private Instant lastActivity; // last operation

}
