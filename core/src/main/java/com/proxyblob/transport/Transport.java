package com.proxyblob.transport;

import com.proxyblob.transport.exception.TransportException;

import java.util.concurrent.atomic.AtomicBoolean;

public interface Transport {

    void send(AtomicBoolean cancelFlag, byte[] data) throws TransportException;

    byte[] receive(AtomicBoolean cancelFlag) throws TransportException;

    boolean isClosed(Throwable e);
}
