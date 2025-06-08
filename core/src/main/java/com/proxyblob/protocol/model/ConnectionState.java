package com.proxyblob.protocol.model;

public enum ConnectionState {
    NEW,        // Pending connection
    CONNECTED,  // Active connection
    CLOSED      // Terminated connection
}
