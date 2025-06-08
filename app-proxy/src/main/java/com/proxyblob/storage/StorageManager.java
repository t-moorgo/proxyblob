package com.proxyblob.storage;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.sas.BlobContainerSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.common.sas.SasProtocol;
import com.proxyblob.protocol.crypto.CipherUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
@RequiredArgsConstructor
public class StorageManager {

    private final BlobServiceClient serviceClient;

    private static final byte[] INFO_KEY = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xB1, 0x0B};

    public String createAgentContainer(Duration expiry) {
        String containerId = UUID.randomUUID().toString();
        BlobContainerClient container = serviceClient.getBlobContainerClient(containerId);
        container.create();

        for (String name : List.of("info", "request", "response")) {
            BlobClient blob = container.getBlobClient(name);
            blob.upload(BinaryData.fromBytes(new byte[0]), true);
            blob.setHttpHeaders(new BlobHttpHeaders().setContentType("application/octet-stream"));
            blob.setMetadata(Map.of("created", Instant.now().toString()));
        }

        String sas = generateSasToken(containerId, expiry);
        String baseUri = serviceClient.getAccountUrl() + "/" + containerId;
        return baseUri + "?" + sas;
    }

    public List<ContainerInfo> listAgentContainers() {
        return serviceClient.listBlobContainers()
                .stream()
                .map(containerItem -> buildContainerInfo(containerItem.getName()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    public boolean validateAgent(String containerId) {
        BlobContainerClient container = serviceClient.getBlobContainerClient(containerId);
        return container.exists() && container.getBlobClient("info").exists();
    }

    public String getAgentInfo(String containerId) {
        return getAgentInfo(serviceClient.getBlobContainerClient(containerId));
    }

    public void deleteAgentContainer(String containerId) {
        BlobContainerClient container = serviceClient.getBlobContainerClient(containerId);
        if (container.exists()) {
            container.delete();
        }
    }

    public BlobContainerClient getContainer(String containerId) {
        return serviceClient.getBlobContainerClient(containerId);
    }

    public String generateSasToken(String containerName, Duration expiry) {
        BlobContainerClient containerClient = serviceClient.getBlobContainerClient(containerName);

        BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(
                OffsetDateTime.now().plus(expiry),
                new BlobContainerSasPermission()
                        .setReadPermission(true)
                        .setWritePermission(true)
        ).setStartTime(OffsetDateTime.now().minusMinutes(5))
                .setProtocol(SasProtocol.HTTPS_HTTP);

        return containerClient.generateSas(values);
    }

    private Optional<ContainerInfo> buildContainerInfo(String containerName) {
        try {
            BlobContainerClient container = serviceClient.getBlobContainerClient(containerName);

            String agentInfo = getAgentInfo(container);
            Instant createdAt = container.getProperties().getLastModified().toInstant();

            BlobClient responseBlob = container.getBlobClient("response");
            Instant lastActivity = responseBlob.exists()
                    ? responseBlob.getProperties().getLastModified().toInstant()
                    : createdAt;

            return Optional.of(new ContainerInfo(
                    containerName,
                    agentInfo,
                    "", // port will be filled later
                    createdAt,
                    lastActivity
            ));

        } catch (Exception e) {
            log.warn("Skipping invalid container '{}': {}", containerName, e.getMessage());
            return Optional.empty();
        }
    }

    private String getAgentInfo(BlobContainerClient container) {
        BlobClient blob = container.getBlobClient("info");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            blob.downloadStream(out);
            return new String(CipherUtil.xor(out.toByteArray(), INFO_KEY), UTF_8);
        } catch (Exception e) {
            log.warn("Failed to read agent info from container '{}': {}", container.getBlobContainerName(), e.getMessage());
            return "<unknown@host>";
        }
    }
}
