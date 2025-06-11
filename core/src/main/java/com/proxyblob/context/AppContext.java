package com.proxyblob.context;

import lombok.Getter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Getter
public class AppContext {

    private final ExecutorService receiverExecutor;
    private final ExecutorService generalExecutor;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public AppContext() {
        this.receiverExecutor = Executors.newSingleThreadExecutor();
        this.generalExecutor = Executors.newCachedThreadPool();
    }

    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            receiverExecutor.shutdownNow();
            generalExecutor.shutdownNow();
        }
    }

    public boolean isStopped() {
        return stopped.get();
    }
}
