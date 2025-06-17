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
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayOutputStream;
import java.time.Duration;

import static com.proxyblob.errorcodes.ErrorCodes.ErrContextCanceled;
import static com.proxyblob.errorcodes.ErrorCodes.ErrNone;
import static com.proxyblob.errorcodes.ErrorCodes.ErrTransportClosed;
import static com.proxyblob.errorcodes.ErrorCodes.ErrTransportError;

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
        System.out.println("[BlobTransport] Sending " + data.length + " bytes");
        return writeBlob(writeBlob, data);
    }

    @Override
    public ReceiveResult receive() {
        System.out.println("[BlobTransport] Waiting for data...");
        return waitForData(readBlob);
    }

    @Override
    public boolean isClosed(byte errorCode) {
        return errorCode == ErrTransportClosed;
    }

    private byte writeBlob(BlockBlobClient blob, byte[] data) {
        Duration delay = InitialRetryDelay;

        while (true) {
            if (context.isStopped()) {
                System.out.println("[BlobTransport] Context stopped during write");
                return ErrContextCanceled;
            }

            CheckResult check = isBlobEmpty(blob);
            if (check.getErrorCode() != ErrNone) {
                System.out.println("[BlobTransport] isBlobEmpty failed: " + check.getErrorCode());
                return check.getErrorCode();
            }

            if (!check.isEmpty()) {
                System.out.println("[BlobTransport] Blob is not empty, retrying...");
                DelayResult delayResult = waitDelay(delay);
                delay = delayResult.getNextDelay();
                if (delayResult.getErrorCode() != ErrNone) {
                    return delayResult.getErrorCode();
                }
                continue;
            }

            delay = InitialRetryDelay;

            try {
                System.out.println("[BlobTransport] Uploading to blob...");
                blob.upload(BinaryData.fromBytes(data), true);
                return ErrNone;
            } catch (BlobStorageException e) {
                System.out.println("[BlobTransport] Upload failed: " + e.getMessage());
                if (context.isStopped()) {
                    return ErrContextCanceled;
                }

                DelayResult delayResult = waitDelay(delay);
                delay = delayResult.getNextDelay();
                if (delayResult.getErrorCode() != ErrNone) {
                    return delayResult.getErrorCode();
                }
            }
        }
    }

    private ReceiveResult waitForData(BlockBlobClient blob) {
        Duration delay = InitialRetryDelay;

        while (true) {
            if (context.isStopped()) {
                System.out.println("[BlobTransport] Context stopped during receive");
                return ReceiveResult.builder().data(null).errorCode(ErrContextCanceled).build();
            }

            CheckResult check = isBlobEmpty(blob);
            if (check.getErrorCode() != ErrNone) {
                System.out.println("[BlobTransport] isBlobEmpty failed during receive: " + check.getErrorCode());
                return ReceiveResult.builder().data(null).errorCode(check.getErrorCode()).build();
            }

            if (check.isEmpty()) {
                DelayResult wait = waitDelay(delay);
                delay = wait.getNextDelay();
                if (wait.getErrorCode() != ErrNone) {
                    return ReceiveResult.builder().data(null).errorCode(wait.getErrorCode()).build();
                }
                continue;
            }

            delay = InitialRetryDelay;

            byte[] data;
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                System.out.println("[BlobTransport] Downloading data from blob...");
                blob.downloadStream(out);
                data = out.toByteArray();
            } catch (Exception e) {
                System.out.println("[BlobTransport] Download failed: " + e.getMessage());
                return ReceiveResult.builder().data(null).errorCode(blobError(e)).build();
            }

            byte clearResult = clearBlob(blob);
            if (clearResult != ErrNone) {
                System.out.println("[BlobTransport] Clear blob failed: " + clearResult);
                return ReceiveResult.builder().data(null).errorCode(clearResult).build();
            }

            System.out.println("[BlobTransport] Received " + data.length + " bytes");
            return ReceiveResult.builder().data(data).errorCode(ErrNone).build();
        }
    }

    private CheckResult isBlobEmpty(BlockBlobClient blob) {
        if (context.isStopped()) {
            System.out.println("[BlobTransport] Context stopped during isBlobEmpty");
            return CheckResult.builder().isEmpty(false).errorCode(ErrContextCanceled).build();
        }

        try {
            BlobProperties props = blob.getProperties();
            boolean empty = props.getBlobSize() == 0;
            System.out.println("[BlobTransport] Blob size: " + props.getBlobSize());
            return CheckResult.builder().isEmpty(empty).errorCode(ErrNone).build();
        } catch (BlobStorageException e) {
            System.out.println("[BlobTransport] Failed to get blob properties: " + e.getMessage());
            return CheckResult.builder().isEmpty(false).errorCode(blobError(e)).build();
        }
    }

    private byte clearBlob(BlockBlobClient blob) {
        Duration delay = InitialRetryDelay;

        while (true) {
            if (context.isStopped()) {
                System.out.println("[BlobTransport] Context stopped during clearBlob");
                return ErrContextCanceled;
            }

            try {
                System.out.println("[BlobTransport] Clearing blob...");
                blob.upload(BinaryData.fromBytes(new byte[0]), true);
                return ErrNone;
            } catch (BlobStorageException e) {
                System.out.println("[BlobTransport] Clear failed: " + e.getMessage());
                byte errCode = blobError(e);
                if (errCode == ErrContextCanceled || errCode == ErrTransportClosed) {
                    return errCode;
                }
            }

            DelayResult result = waitDelay(delay);
            delay = result.getNextDelay();
            if (result.getErrorCode() != ErrNone) {
                return result.getErrorCode();
            }
        }
    }

    private byte blobError(Throwable err) {
        if (err == null) return ErrNone;

        if (err instanceof InterruptedException) {
            System.out.println("[BlobTransport] InterruptedException during blobError");
            Thread.currentThread().interrupt();
            return ErrContextCanceled;
        }

        if (err instanceof BlobStorageException e) {
            String code = e.getErrorCode() != null ? e.getErrorCode().toString() : StringUtils.EMPTY;
            System.out.println("[BlobTransport] BlobStorageException code: " + code);

            if (BlobErrorCode.CONTAINER_NOT_FOUND.toString().equals(code)
                    || BlobErrorCode.CONTAINER_BEING_DELETED.toString().equals(code)
                    || BlobErrorCode.ACCOUNT_BEING_CREATED.toString().equals(code)) {
                return ErrTransportClosed;
            }
        }

        return ErrTransportError;
    }

    private DelayResult waitDelay(Duration retryDelay) {
        if (context.isStopped()) {
            System.out.println("[BlobTransport] Context stopped during waitDelay");
            return DelayResult.builder().nextDelay(Duration.ZERO).errorCode(ErrContextCanceled).build();
        }

        long millis = retryDelay.toMillis();
        long slept = 0;

        try {
            while (slept < millis) {
                if (context.isStopped()) {
                    return DelayResult.builder().nextDelay(Duration.ZERO).errorCode(ErrContextCanceled).build();
                }
                long chunk = Math.min(100, millis - slept);
                Thread.sleep(chunk);
                slept += chunk;
            }
        } catch (InterruptedException e) {
            System.out.println("[BlobTransport] Interrupted during waitDelay");
            Thread.currentThread().interrupt();
            return DelayResult.builder().nextDelay(Duration.ZERO).errorCode(ErrContextCanceled).build();
        }

        long next = Math.min((long) (retryDelay.toMillis() * BackoffFactor), MaxRetryDelay.toMillis());
        return DelayResult.builder().nextDelay(Duration.ofMillis(next)).errorCode(ErrNone).build();
    }
}
