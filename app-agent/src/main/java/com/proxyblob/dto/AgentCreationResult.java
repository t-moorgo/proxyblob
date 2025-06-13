package com.proxyblob.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public final class AgentCreationResult {
    private Agent agent;
    private int status;
}
