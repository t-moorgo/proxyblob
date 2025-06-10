package com.proxyblob.transport;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public class BlobTransport implements Transport {

    private final BlobClient readBlob;
    private final BlobClient writeBlob;

    private static final Duration InitialRetryDelay = Duration.ofMillis(50);
    private static final Duration MaxRetryDelay = Duration.ofSeconds(3);
    private static final double BackoffFactor = 1.5;

    public BlobTransport(BlobClient readBlob, BlobClient writeBlob) {
        this.readBlob = readBlob;
        this.writeBlob = writeBlob;
    }

    @Override
    public byte send(AtomicBoolean cancelFlag, byte[] data) {
        return writeBlob(writeBlob, data, cancelFlag);
    }

    @Override
    public ReceiveResult receive(AtomicBoolean cancelFlag) {
        return waitForData(readBlob, cancelFlag);
    }

    @Override
    public boolean isClosed(byte errorCode) {
        return errorCode == Transport.ErrTransportClosed;
    }

    private byte writeBlob(BlobClient blob, byte[] data, AtomicBoolean cancelFlag) {
        Duration delay = InitialRetryDelay;

        while (true) {
            if (isCanceled(cancelFlag)) {
                return Transport.ErrContextCanceled;
            }

            // Проверка, пуст ли blob
            CheckResult check = isBlobEmpty(blob, cancelFlag);
            if (check.errorCode() != Transport.ErrNone) {
                return check.errorCode();
            }

            if (!check.isEmpty()) {
                // Ждём и увеличиваем задержку, если не пустой
                DelayResult delayResult = waitDelay(delay, cancelFlag);
                delay = delayResult.nextDelay();
                if (delayResult.errorCode() != Transport.ErrNone) {
                    return delayResult.errorCode();
                }
                continue;
            }

            // Сброс задержки при пустом blob
            delay = InitialRetryDelay;

            try {
                // Загрузка данных
                blob.upload(BinaryData.fromBytes(data), true);
                return Transport.ErrNone;
            } catch (BlobStorageException e) {
                // Проверка отмены после ошибки
                if (isCanceled(cancelFlag)) {
                    return Transport.ErrContextCanceled;
                }

                // Ждём и увеличиваем задержку перед повтором
                DelayResult delayResult = waitDelay(delay, cancelFlag);
                delay = delayResult.nextDelay();
                if (delayResult.errorCode() != Transport.ErrNone) {
                    return delayResult.errorCode();
                }
            }
        }
    }

    private ReceiveResult waitForData(BlobClient blob, AtomicBoolean cancelFlag) {
        Duration delay = InitialRetryDelay;

        while (true) {
            if (isCanceled(cancelFlag)) {
                return new ReceiveResult(null, Transport.ErrContextCanceled);
            }

            CheckResult check = isBlobEmpty(blob, cancelFlag);
            if (check.errorCode() != Transport.ErrNone) {
                return new ReceiveResult(null, check.errorCode());
            }

            if (check.isEmpty()) {
                DelayResult wait = waitDelay(delay, cancelFlag);
                delay = wait.nextDelay();
                if (wait.errorCode() != Transport.ErrNone) {
                    return new ReceiveResult(null, wait.errorCode());
                }
                continue;
            }

            // Reset delay after finding data
            delay = InitialRetryDelay;

            byte[] data;
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                blob.downloadStream(out);
                data = out.toByteArray();
            } catch (Exception e) {
                return new ReceiveResult(null, blobError(e));
            }

            // Clear the blob (no retries)
            byte clearResult = clearBlob(blob, cancelFlag);
            if (clearResult != Transport.ErrNone) {
                return new ReceiveResult(null, clearResult);
            }

            return new ReceiveResult(data, Transport.ErrNone);
        }
    }

    private CheckResult isBlobEmpty(BlobClient blob, AtomicBoolean cancelFlag) {
        if (isCanceled(cancelFlag)) {
            return new CheckResult(false, Transport.ErrContextCanceled);
        }

        try {
            BlobProperties props = blob.getProperties();
            boolean empty = props.getBlobSize() == 0;
            return new CheckResult(empty, Transport.ErrNone);
        } catch (BlobStorageException e) {
            return new CheckResult(false, blobError(e));
        }
    }

    private record CheckResult(boolean isEmpty, byte errorCode) {}

    private byte clearBlob(BlobClient blob, AtomicBoolean cancelFlag) {
        Duration delay = InitialRetryDelay;

        while (true) {
            if (isCanceled(cancelFlag)) {
                return Transport.ErrContextCanceled;
            }

            try {
                blob.upload(BinaryData.fromBytes(new byte[0]), true);
                return Transport.ErrNone;
            } catch (BlobStorageException e) {
                // map error but ignore code – always retry unless context canceled
            }

            DelayResult result = waitDelay(delay, cancelFlag);
            delay = result.nextDelay();
            if (result.errorCode() != Transport.ErrNone) {
                return result.errorCode();
            }
        }
    }

    private byte blobError(Throwable err) {
        // Quick check for null
        if (err == null) {
            return Transport.ErrNone;
        }

        // Check for context cancellation
        if (err instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return Transport.ErrContextCanceled;
        }

        // Check for container-related errors
        if (err instanceof BlobStorageException e) {
            String code = e.getErrorCode() != null ? e.getErrorCode().toString() : "";

            if ("ContainerNotFound".equals(code)
                    || "ContainerBeingDeleted".equals(code)
                    || "AccountBeingCreated".equals(code)) {
                return Transport.ErrTransportClosed;
            }
        }

        // All other errors are treated as generic transport errors
        return Transport.ErrTransportError;
    }

    private DelayResult waitDelay(Duration retryDelay, AtomicBoolean cancelFlag) {
        if (isCanceled(cancelFlag)) {
            return new DelayResult(Duration.ZERO, Transport.ErrContextCanceled);
        }

        long millis = retryDelay.toMillis();
        long slept = 0;

        try {
            while (slept < millis) {
                if (isCanceled(cancelFlag)) {
                    return new DelayResult(Duration.ZERO, Transport.ErrContextCanceled);
                }
                long chunk = Math.min(100, millis - slept);
                Thread.sleep(chunk);
                slept += chunk;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DelayResult(Duration.ZERO, Transport.ErrContextCanceled);
        }

        // Increase delay with backoff factor
        long next = Math.min((long) (retryDelay.toMillis() * BackoffFactor), MaxRetryDelay.toMillis());
        return new DelayResult(Duration.ofMillis(next), Transport.ErrNone);
    }

    private record DelayResult(Duration nextDelay, byte errorCode) {}

    private boolean isCanceled(AtomicBoolean cancelFlag) {
        return cancelFlag != null && cancelFlag.get();
    }
}
