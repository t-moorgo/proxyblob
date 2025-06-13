package com.proxyblob.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public final class ParseResult {
    private String storageUrl;
    private String containerId;
    private String sasToken;
    private int errorCode;
}
