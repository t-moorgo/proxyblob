package com.proxyblob.dto;

import com.proxyblob.Agent;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AgentCreationResult {
    private Agent agent;
    private int status;
}
