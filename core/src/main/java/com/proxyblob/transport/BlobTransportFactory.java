package com.proxyblob.transport;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import lombok.experimental.UtilityClass;

@UtilityClass
public class BlobTransportFactory {
    public static BlobTransport create(BlobContainerClient container, String readBlobName, String writeBlobName) {
        if (!container.exists()) {
            container.create();
        }

        BlobClient readBlob = container.getBlobClient(readBlobName);
        BlobClient writeBlob = container.getBlobClient(writeBlobName);

        return new BlobTransport(readBlob, writeBlob);
    }
}
