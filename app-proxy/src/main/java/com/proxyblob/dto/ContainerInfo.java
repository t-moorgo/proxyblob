package com.proxyblob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Data
@AllArgsConstructor
public class ContainerInfo {
    private String id;
    private String agentInfo;
    private String proxyPort;
    private Instant createdAt;
    private Instant lastActivity;

}
