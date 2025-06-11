package com.proxyblob.state;

import com.proxyblob.config.Config;
import com.proxyblob.context.AppContext;
import com.proxyblob.proxy.server.ProxyServer;
import com.proxyblob.storage.StorageManager;
import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.ConcurrentHashMap;

public class AppState {

    @Getter
    @Setter
    private static Config config;

    @Getter
    @Setter
    private static StorageManager storageManager;

    @Getter
    @Setter
    private static AppContext context;

    @Setter
    @Getter
    private static String selectedAgent;

    @Setter
    @Getter
    private static String cliPrompt = "proxyblob »";

    private static final ConcurrentHashMap<String, ProxyServer> runningProxies = new ConcurrentHashMap<>();

    public static boolean isProxyRunning(String containerId) {
        return runningProxies.containsKey(containerId);
    }

    public static ProxyServer getProxy(String containerId) {
        return runningProxies.get(containerId);
    }

    public static void removeProxy(String containerId) {
        runningProxies.remove(containerId);
    }

    public static void registerProxy(String containerId, ProxyServer server) {
        runningProxies.put(containerId, server);
    }

}
