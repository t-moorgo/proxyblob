package com.proxyblob.transport;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.proxyblob.transport.exception.BlobStateReadException;
import com.proxyblob.transport.exception.TransportClosedException;
import com.proxyblob.transport.exception.TransportException;
import com.proxyblob.transport.exception.TransportFailureException;
import lombok.RequiredArgsConstructor;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiredArgsConstructor
public class BlobTransport implements Transport {

    private final BlobClient readBlobClient;
    private final BlobClient writeBlobClient;

    private final Object readLock = new Object();
    private final Object writeLock = new Object();

    @Override
    public void send(AtomicBoolean cancelFlag, byte[] data) throws TransportException {
        synchronized (writeLock) {
            TransportUtil.retry(cancelFlag, () -> {
                if (!isBlobEmpty(writeBlobClient)) {
                    throw new TransportFailureException("Blob is not empty");
                }
                writeBlobClient.upload(BinaryData.fromBytes(data), true);
            });
        }
    }

    @Override
    public byte[] receive(AtomicBoolean cancelFlag) throws TransportException {
        synchronized (readLock) {
            final byte[][] result = new byte[1][];
            TransportUtil.retry(cancelFlag, () -> {
                if (isBlobEmpty(readBlobClient)) {
                    throw new TransportFailureException("Blob is empty");
                }

                ByteArrayOutputStream output = new ByteArrayOutputStream();
                readBlobClient.downloadStream(output);
                result[0] = output.toByteArray();

                TransportUtil.retry(cancelFlag, () ->
                        readBlobClient.upload(BinaryData.fromBytes(new byte[0]), true));
            });
            return result[0];
        }
    }

    @Override
    public boolean isClosed(Throwable e) {
        return TransportUtil.isTransportClosed(e);
    }

    private boolean isBlobEmpty(BlobClient blobClient) throws TransportException {
        try {
            return blobClient.getProperties().getBlobSize() == 0;
        } catch (BlobStorageException e) {
            String code = e.getErrorCode() != null ? e.getErrorCode().toString() : "";
            if ("ContainerNotFound".equals(code)
                    || "ContainerBeingDeleted".equals(code)
                    || "AccountBeingCreated".equals(code)) {
                throw new TransportClosedException();
            }

            if (e.getStatusCode() == 404) return true;

            throw new BlobStateReadException("Failed to get blob properties", e);
        }
    }
}
