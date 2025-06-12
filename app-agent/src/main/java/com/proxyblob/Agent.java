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

@Getter
@RequiredArgsConstructor
public class Agent {

    public static final int Success = 0;
    public static final int ErrContextCanceled = 1;
    public static final int ErrNoConnectionString = 2;
    public static final int ErrConnectionStringError = 3;
    public static final int ErrInfoBlobError = 4;
    public static final int ErrContainerNotFound = 5;

    private static final String InfoBlobName = "info";
    private static final String RequestBlobName = "request";
    private static final String ResponseBlobName = "response";
    private static final byte[] InfoKey = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xB1, 0x0B};

    private final BlobContainerClient containerClient; // аналог azblob.ContainerURL
    private final SocksHandler handler;                // аналог proxy.SocksHandler

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
        // Запись agent info в blob
        int result = writeInfoBlob();
        if (result != Success) {
            stop();
            return ErrContainerNotFound;
        }

        // Запускаем мониторинг контейнера
        context.getGeneralExecutor().submit(() -> healthCheck(context));

        // Запускаем обработчик SOCKS
        handler.start("");

        // Ждём завершения по флагу контекста
        while (!context.isStopped()) {
            try {
                Thread.sleep(200); // Можно уменьшить/увеличить по ситуации
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

                blob.getProperties(); // Попытка получить метаданные блоба

            } catch (BlobStorageException e) {
                String code = e.getErrorCode().toString();
                if ("ContainerNotFound".equals(code) || "ContainerBeingDeleted".equals(code)) {
                    stop();
                }
            } catch (Exception e) {
                // Прочие ошибки — можно залогировать позже
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

            String containerId = path.substring(1); // Remove leading '/'
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

