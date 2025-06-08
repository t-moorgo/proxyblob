package com.proxyblob;

import com.proxyblob.config.Config;
import com.proxyblob.proxy.server.ProxyServer;
import com.proxyblob.storage.StorageManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@Setter
@RequiredArgsConstructor
public class ApplicationContext {

    private final Config config;
    private final StorageManager storageManager;
    private final Map<String, ProxyServer> runningProxies = new ConcurrentHashMap<>();
    private String selectedAgent;

    public void clearSelectedAgent() {
        this.selectedAgent = null;
    }
}
