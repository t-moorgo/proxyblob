package com.proxyblob.storage;

import com.azure.core.http.rest.PagedIterable;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobContainerItem;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.sas.BlobContainerSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.storage.common.sas.SasProtocol;
import com.proxyblob.config.Config;
import com.proxyblob.dto.ContainerCreationResult;
import com.proxyblob.dto.ContainerInfo;
import com.proxyblob.protocol.CryptoUtil;
import com.proxyblob.proxy.server.ProxyServer;
import com.proxyblob.state.AppState;
import com.proxyblob.util.Constants;
import lombok.Getter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.proxyblob.util.Constants.InfoBlobName;
import static com.proxyblob.util.Constants.InfoKey;

@Getter
public class StorageManager {

    private final BlobServiceClient serviceClient;
    private final String accountName;
    private final String accountKey;

    public StorageManager(Config config) {
        this.accountName = config.getStorageAccountName();
        this.accountKey = config.getStorageAccountKey();

        String endpoint = config.getStorageURL() != null && !config.getStorageURL().isEmpty()
                ? config.getStorageURL() + "/" + accountName
                : String.format("https://%s.blob.core.windows.net", accountName);

        StorageSharedKeyCredential credential = new StorageSharedKeyCredential(accountName, accountKey);
        this.serviceClient = new BlobServiceClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .buildClient();
    }

    public ContainerCreationResult createAgentContainer(Duration expiry) {
        String containerId = UUID.randomUUID().toString();
        BlobContainerClient containerClient = serviceClient.getBlobContainerClient(containerId);

        try {
            containerClient.create();

            List<String> blobNames = List.of(Constants.InfoBlobName, Constants.RequestBlobName, Constants.ResponseBlobName);

            for (String blobName : blobNames) {
                BlockBlobClient blobClient = containerClient
                        .getBlobClient(blobName)
                        .getBlockBlobClient();

                Map<String, String> metadata = Map.of(
                        "created", OffsetDateTime.now().format(DateTimeFormatter.ISO_INSTANT)
                );

                blobClient.upload(
                        new ByteArrayInputStream(new byte[0]),
                        0,
                        true
                );
                blobClient.setHttpHeaders(new BlobHttpHeaders().setContentType("application/octet-stream"));
                blobClient.setMetadata(metadata);
            }

            String sasToken = generateSasToken(containerId, expiry);

            URI baseUri = new URI(serviceClient.getAccountUrl());
            String connectionString = baseUri.resolve("/" + containerId) + "?" + sasToken;

            return ContainerCreationResult.builder()
                    .containerId(containerId)
                    .connectionString(connectionString)
                    .build();
        } catch (Exception e) {
            //TODO что то написать
            try {
                containerClient.delete();
            } catch (Exception ignored) {
            }
            throw new RuntimeException("Failed to create agent container", e);
        }
    }

    public String generateSasToken(String containerName, Duration expiry) {
        OffsetDateTime startTime = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);

        OffsetDateTime expiryTime = OffsetDateTime.now(ZoneOffset.UTC).plus(expiry);

        BlobContainerSasPermission permissions = new BlobContainerSasPermission()
                .setReadPermission(true)
                .setWritePermission(true);

        BlobServiceSasSignatureValues sasValues = new BlobServiceSasSignatureValues(expiryTime, permissions)
                .setStartTime(startTime)
                .setProtocol(SasProtocol.HTTPS_HTTP)
                .setContainerName(containerName);

        BlobContainerClient containerClient = serviceClient.getBlobContainerClient(containerName);
        return containerClient.generateSas(sasValues);
    }

    public List<ContainerInfo> listAgentContainers() {
        List<ContainerInfo> containers = new ArrayList<>();

        PagedIterable<BlobContainerItem> containerItems = serviceClient.listBlobContainers();
        for (BlobContainerItem containerItem : containerItems) {
            String containerName = containerItem.getName();
            BlobContainerClient containerClient = serviceClient.getBlobContainerClient(containerName);

            BlockBlobClient infoBlob = containerClient
                    .getBlobClient(Constants.InfoBlobName)
                    .getBlockBlobClient();

            if (!infoBlob.exists()) {
                continue;
            }

            String agentInfo;
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                infoBlob.download(outputStream);
                byte[] decrypted = CryptoUtil.xor(outputStream.toByteArray(), Constants.InfoKey);
                agentInfo = new String(decrypted, StandardCharsets.UTF_8);
            } catch (IOException e) {
                //TODO что то написать
                continue;
            }

            OffsetDateTime lastActivity;
            try {
                BlockBlobClient responseBlob = containerClient
                        .getBlobClient(Constants.ResponseBlobName)
                        .getBlockBlobClient();

                lastActivity = responseBlob.getProperties().getLastModified();
            } catch (BlobStorageException e) {
                lastActivity = containerItem.getProperties().getLastModified();
            }

            String proxyPort = null;
            if (AppState.isProxyRunning(containerName)) {
                ProxyServer proxy = AppState.getProxy(containerName);
                if (proxy != null && proxy.getListener() != null) {
                    try {
                        proxyPort = Integer.toString(proxy.getListener().getLocalPort());
                    } catch (Exception ignored) {
                    }
                }
            }

            ContainerInfo containerInfo = ContainerInfo.builder()
                    .id(containerName)
                    .agentInfo(agentInfo)
                    .proxyPort(proxyPort)
                    .createdAt(containerItem.getProperties().getLastModified().toInstant())
                    .lastActivity(lastActivity.toInstant())
                    .build();
            containers.add(containerInfo);
        }

        return containers;
    }

    public void deleteAgentContainer(String containerId) {
        if (AppState.isProxyRunning(containerId)) {
            ProxyServer proxyServer = AppState.getProxy(containerId);
            if (proxyServer != null) {
                proxyServer.stop();
            }
            AppState.removeProxy(containerId);
        }

        try {
            serviceClient.getBlobContainerClient(containerId).delete();
        } catch (BlobStorageException e) {
            if (e.getErrorCode() == BlobErrorCode.CONTAINER_NOT_FOUND) {
                throw new IllegalStateException("Container not found: " + containerId, e);
            }
            throw new RuntimeException("Failed to delete container: " + containerId, e);
        }
    }

    public void validateAgent(String containerId) {
        BlockBlobClient blobClient = serviceClient
                .getBlobContainerClient(containerId)
                .getBlobClient(InfoBlobName)
                .getBlockBlobClient();

        try {
            blobClient.getProperties();
        } catch (BlobStorageException e) {
            if (e.getErrorCode() == BlobErrorCode.CONTAINER_NOT_FOUND) {
                throw new IllegalArgumentException("Agent container " + containerId + " does not exist");
            }
            throw new IllegalStateException("Invalid agent container " + containerId + ": " + e.getMessage(), e);
        }
    }

    public String getSelectedAgentInfo(String selectedAgent) {
        if (selectedAgent == null || selectedAgent.isEmpty()) {
            throw new IllegalStateException("No agent selected. Use 'agent use <container-id>' first.");
        }

        BlobContainerClient containerClient = serviceClient.getBlobContainerClient(selectedAgent);
        BlockBlobClient blobClient = containerClient.getBlobClient(InfoBlobName).getBlockBlobClient();

        if (!blobClient.exists()) {
            throw new IllegalStateException("Info blob not found for container: " + selectedAgent);
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            blobClient.download(outputStream);
            byte[] encrypted = outputStream.toByteArray();
            byte[] decrypted = CryptoUtil.xor(encrypted, InfoKey);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read agent info", e);
        }
    }
}
