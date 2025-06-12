package com.proxyblob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ParseResult {
    private String storageUrl;
    private String containerId;
    private String sasToken;
    private int errorCode;
}
