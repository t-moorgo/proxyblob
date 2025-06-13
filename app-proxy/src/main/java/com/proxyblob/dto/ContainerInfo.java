package com.proxyblob.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public final class ContainerInfo {
    private String id;
    private String agentInfo;
    private String proxyPort;
    private Instant createdAt;
    private Instant lastActivity;

}
