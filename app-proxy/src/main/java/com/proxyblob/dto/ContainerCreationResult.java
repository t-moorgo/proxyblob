package com.proxyblob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ContainerCreationResult {
    String containerId;
    String connectionString;
}
