package com.proxyblob.dto;

import com.azure.storage.blob.BlobContainerClient;
import com.proxyblob.proxy.socks.SocksHandler;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public final class Agent {
    private BlobContainerClient containerClient;
    private SocksHandler handler;
}
