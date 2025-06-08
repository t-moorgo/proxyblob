package com.proxyblob.transport;

import com.azure.storage.blob.models.BlobStorageException;
import com.proxyblob.transport.exception.TransportCanceledException;
import com.proxyblob.transport.exception.TransportClosedException;
import lombok.experimental.UtilityClass;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

@UtilityClass
public class TransportUtil {

    private static final Duration INITIAL_DELAY = Duration.ofMillis(50);
    private static final Duration MAX_DELAY = Duration.ofSeconds(3);
    private static final double BACKOFF_FACTOR = 1.5;

    public static void retry(AtomicBoolean cancelFlag, ThrowingRunnable action) throws TransportCanceledException {
        Duration delay = INITIAL_DELAY;
        while (true) {
            try {
                checkCanceled(cancelFlag);
                action.run();
                return;
            } catch (Exception e) {
                checkCanceled(cancelFlag);
                sleepIgnoreInterrupt(delay, cancelFlag);
                delay = increaseDelay(delay);
            }
        }
    }

    public static void sleepWithCancel(Duration delay, AtomicBoolean cancelFlag) throws TransportCanceledException {
        long millis = delay.toMillis();
        long slept = 0;
        try {
            while (slept < millis) {
                checkCanceled(cancelFlag);
                long sleepChunk = Math.min(100, millis - slept);
                Thread.sleep(sleepChunk);
                slept += sleepChunk;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportCanceledException("Operation canceled during sleep");
        }
    }

    public static void sleepIgnoreInterrupt(Duration delay, AtomicBoolean cancelFlag) throws TransportCanceledException {
        try {
            sleepWithCancel(delay, cancelFlag);
        } catch (TransportCanceledException e) {
            throw e;
        } catch (Exception ignored) {
        }
    }

    public static void checkCanceled(AtomicBoolean cancelFlag) throws TransportCanceledException {
        if (cancelFlag != null && cancelFlag.get()) {
            throw new TransportCanceledException("Operation canceled by context");
        }
    }

    public static Duration increaseDelay(Duration delay) {
        long newDelay = Math.min((long) (delay.toMillis() * BACKOFF_FACTOR), MAX_DELAY.toMillis());
        return Duration.ofMillis(newDelay);
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static boolean isTransportClosed(Throwable e) {
        if (e instanceof TransportClosedException) return true;
        if (e instanceof BlobStorageException bse) {
            String code = bse.getErrorCode() != null ? bse.getErrorCode().toString() : "";
            return "ContainerNotFound".equals(code)
                    || "ContainerBeingDeleted".equals(code)
                    || "AccountBeingCreated".equals(code);
        }
        return false;
    }
}
