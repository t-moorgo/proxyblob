package com.proxyblob;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlockBlobSimpleUploadOptions;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.proxyblob.context.AppContext;
import com.proxyblob.dto.AgentCreationResult;
import com.proxyblob.dto.ParseResult;
import com.proxyblob.protocol.CryptoUtil;
import com.proxyblob.proxy.socks.SocksHandler;
import com.proxyblob.transport.BlobTransport;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.proxyblob.constants.Constants.ErrConnectionStringError;
import static com.proxyblob.constants.Constants.ErrContainerNotFound;
import static com.proxyblob.constants.Constants.ErrContextCanceled;
import static com.proxyblob.constants.Constants.ErrInfoBlobError;
import static com.proxyblob.constants.Constants.ErrNoConnectionString;
import static com.proxyblob.constants.Constants.InfoBlobName;
import static com.proxyblob.constants.Constants.InfoKey;
import static com.proxyblob.constants.Constants.RequestBlobName;
import static com.proxyblob.constants.Constants.ResponseBlobName;
import static com.proxyblob.constants.Constants.Success;

@Getter
@RequiredArgsConstructor
public class Agent {

    private final BlobContainerClient containerClient;
    private final SocksHandler handler;

    public AgentCreationResult create(AppContext context, String connString) {
        ParseResult parsed = parseConnectionString(connString);
        if (parsed.getErrorCode() != Success) {
            return new AgentCreationResult(null, parsed.getErrorCode());
        }

        try {
            BlobContainerClient containerClient = new BlobContainerClientBuilder()
                    .endpoint(parsed.getStorageUrl())
                    .sasToken(parsed.getSasToken())
                    .containerName(parsed.getContainerId())
                    .buildClient();

            BlockBlobClient requestBlob = containerClient.getBlobClient(RequestBlobName).getBlockBlobClient();
            BlockBlobClient responseBlob = containerClient.getBlobClient(ResponseBlobName).getBlockBlobClient();

            BlobTransport transport = new BlobTransport(requestBlob, responseBlob, context);
            SocksHandler handler = new SocksHandler(transport, context);

            Agent agent = new Agent(containerClient, handler);
            return new AgentCreationResult(agent, Success);
        } catch (Exception e) {
            return new AgentCreationResult(null, ErrConnectionStringError);
        }
    }

    public int start(AppContext context) {
        int result = writeInfoBlob();
        if (result != Success) {
            stop();
            return ErrContainerNotFound;
        }

        context.getGeneralExecutor().submit(() -> healthCheck(context));

        handler.start("");

        while (!context.isStopped()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ErrContextCanceled;
            }
        }

        return Success;
    }

    private void stop() {
        handler.stop();
    }

    private void healthCheck(AppContext context) {
        ScheduledExecutorService scheduler = context.getScheduler();
        Runnable task = () -> {
            if (context.isStopped()) {
                if (!scheduler.isShutdown()) {
                    scheduler.shutdown();
                }
                return;
            }

            try {
                BlockBlobClient blob = containerClient
                        .getBlobClient(InfoBlobName)
                        .getBlockBlobClient();

                blob.getProperties();

            } catch (BlobStorageException e) {
                String code = e.getErrorCode().toString();
                if ("ContainerNotFound".equals(code) || "ContainerBeingDeleted".equals(code)) {
                    stop();
                }
            } catch (Exception e) {
                //TODO что то прописать
            }
        };

        scheduler.scheduleAtFixedRate(task, 0, 30, TimeUnit.SECONDS);
    }

    private int writeInfoBlob() {
        try {
            String info = getCurrentInfo();
            byte[] encrypted = CryptoUtil.xor(info.getBytes(StandardCharsets.UTF_8), InfoKey);

            BlockBlobClient blob = containerClient.getBlobClient(InfoBlobName).getBlockBlobClient();

            BlockBlobSimpleUploadOptions options = new BlockBlobSimpleUploadOptions(
                    new ByteArrayInputStream(encrypted), encrypted.length)
                    .setHeaders(new BlobHttpHeaders().setContentType("text/plain"));

            blob.uploadWithResponse(options, null, Context.NONE);
            return Success;

        } catch (BlobStorageException e) {
            if ("ContainerNotFound".equals(e.getErrorCode().toString()) ||
                    "ContainerBeingDeleted".equals(e.getErrorCode().toString())) {
                return ErrContainerNotFound;
            }
            return ErrInfoBlobError;

        } catch (Exception e) {
            return ErrInfoBlobError;
        }
    }

    private ParseResult parseConnectionString(String connString) {
        if (connString == null || connString.isEmpty()) {
            return new ParseResult(null, null, null, ErrNoConnectionString);
        }

        try {
            byte[] decodedBytes = Base64.getUrlDecoder().decode(connString);
            String decoded = new String(decodedBytes, StandardCharsets.UTF_8);

            URL url = new URL(decoded);
            String path = url.getPath();
            if (path == null || path.length() <= 1) {
                return error();
            }

            String containerId = path.substring(1);
            String query = url.getQuery();
            if (query == null || query.isEmpty()) {
                return error();
            }

            String storageUrl = url.getProtocol() + "://" + url.getHost();
            return new ParseResult(storageUrl, containerId, query, Success);
        } catch (Exception e) {
            return error();
        }
    }

    private ParseResult error() {
        return new ParseResult(null, null, null, ErrConnectionStringError);
    }

    private String getCurrentInfo() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            hostname = "unknown";
        }

        String username = System.getProperty("user.name");
        if (username == null || username.isEmpty()) {
            username = "unknown";
        }

        return username + "@" + hostname;
    }
}

