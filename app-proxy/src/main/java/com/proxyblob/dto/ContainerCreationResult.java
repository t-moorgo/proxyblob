package com.proxyblob.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public final class ContainerCreationResult {
    private String containerId;
    private String connectionString;
}
