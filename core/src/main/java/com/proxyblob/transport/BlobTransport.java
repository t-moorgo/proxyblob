package com.proxyblob.transport;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.proxyblob.context.AppContext;
import com.proxyblob.proxy.socks.dto.ReceiveResult;
import com.proxyblob.transport.dto.CheckResult;
import com.proxyblob.transport.dto.DelayResult;
import lombok.RequiredArgsConstructor;

import java.io.ByteArrayOutputStream;
import java.time.Duration;

@RequiredArgsConstructor
public class BlobTransport implements Transport {

    private final BlockBlobClient readBlob;
    private final BlockBlobClient writeBlob;
    private final AppContext context;

    private static final Duration InitialRetryDelay = Duration.ofMillis(50);
    private static final Duration MaxRetryDelay = Duration.ofSeconds(3);
    private static final double BackoffFactor = 1.5;

    @Override
    public byte send(byte[] data) {
        return writeBlob(writeBlob, data);
    }

    @Override
    public ReceiveResult receive() {
        return waitForData(readBlob);
    }

    @Override
    public boolean isClosed(byte errorCode) {
        return errorCode == Transport.ErrTransportClosed;
    }

    private byte writeBlob(BlockBlobClient blob, byte[] data) {
        Duration delay = InitialRetryDelay;

        while (true) {
            if (context.isStopped()) {
                return Transport.ErrContextCanceled;
            }

            CheckResult check = isBlobEmpty(blob);
            if (check.getErrorCode() != Transport.ErrNone) {
                return check.getErrorCode();
            }

            if (!check.isEmpty()) {
                DelayResult delayResult = waitDelay(delay);
                delay = delayResult.getNextDelay();
                if (delayResult.getErrorCode() != Transport.ErrNone) {
                    return delayResult.getErrorCode();
                }
                continue;
            }

            delay = InitialRetryDelay;

            try {
                blob.upload(BinaryData.fromBytes(data), true);
                return Transport.ErrNone;
            } catch (BlobStorageException e) {
                if (context.isStopped()) {
                    return Transport.ErrContextCanceled;
                }

                DelayResult delayResult = waitDelay(delay);
                delay = delayResult.getNextDelay();
                if (delayResult.getErrorCode() != Transport.ErrNone) {
                    return delayResult.getErrorCode();
                }
            }
        }
    }

    private ReceiveResult waitForData(BlockBlobClient blob) {
        Duration delay = InitialRetryDelay;

        while (true) {
            if (context.isStopped()) {
                return new ReceiveResult(null, Transport.ErrContextCanceled);
            }

            CheckResult check = isBlobEmpty(blob);
            if (check.getErrorCode() != Transport.ErrNone) {
                return new ReceiveResult(null, check.getErrorCode());
            }

            if (check.isEmpty()) {
                DelayResult wait = waitDelay(delay);
                delay = wait.getNextDelay();
                if (wait.getErrorCode() != Transport.ErrNone) {
                    return new ReceiveResult(null, wait.getErrorCode());
                }
                continue;
            }

            delay = InitialRetryDelay;

            byte[] data;
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                blob.downloadStream(out);
                data = out.toByteArray();
            } catch (Exception e) {
                return new ReceiveResult(null, blobError(e));
            }

            byte clearResult = clearBlob(blob);
            if (clearResult != Transport.ErrNone) {
                return new ReceiveResult(null, clearResult);
            }

            return new ReceiveResult(data, Transport.ErrNone);
        }
    }

    private CheckResult isBlobEmpty(BlockBlobClient blob) {
        if (context.isStopped()) {
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

    private byte clearBlob(BlockBlobClient blob) {
        Duration delay = InitialRetryDelay;

        while (true) {
            if (context.isStopped()) {
                return Transport.ErrContextCanceled;
            }

            try {
                blob.upload(BinaryData.fromBytes(new byte[0]), true);
                return Transport.ErrNone;
            } catch (BlobStorageException e) {
                //TODO что то сделать
            }

            DelayResult result = waitDelay(delay);
            delay = result.getNextDelay();
            if (result.getErrorCode() != Transport.ErrNone) {
                return result.getErrorCode();
            }
        }
    }

    private byte blobError(Throwable err) {
        if (err == null) {
            return Transport.ErrNone;
        }

        if (err instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return Transport.ErrContextCanceled;
        }

        if (err instanceof BlobStorageException e) {
            String code = e.getErrorCode() != null ? e.getErrorCode().toString() : "";

            if (BlobErrorCode.CONTAINER_NOT_FOUND.toString().equals(code)
                    || BlobErrorCode.CONTAINER_BEING_DELETED.toString().equals(code)
                    || BlobErrorCode.ACCOUNT_BEING_CREATED.toString().equals(code)) {
                return Transport.ErrTransportClosed;
            }
        }

        return Transport.ErrTransportError;
    }

    private DelayResult waitDelay(Duration retryDelay) {
        if (context.isStopped()) {
            return new DelayResult(Duration.ZERO, Transport.ErrContextCanceled);
        }

        long millis = retryDelay.toMillis();
        long slept = 0;

        try {
            while (slept < millis) {
                if (context.isStopped()) {
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

        long next = Math.min((long) (retryDelay.toMillis() * BackoffFactor), MaxRetryDelay.toMillis());
        return new DelayResult(Duration.ofMillis(next), Transport.ErrNone);
    }
}
