package com.proxyblob.protocol.handler;

import com.proxyblob.protocol.model.Connection;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionManager {

    private final Map<UUID, Connection> connections = new ConcurrentHashMap<>();

    public void add(Connection connection) {
        connections.put(connection.getId(), connection);
    }

    public Connection get(UUID id) {
        return connections.get(id);
    }

    public void remove(UUID id) {
        connections.remove(id);
    }

    public void closeAll() {
        connections.values().forEach(Connection::close);
        connections.clear();
    }
}
