package com.proxyblob.util;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlockBlobSimpleUploadOptions;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.proxyblob.context.AppContext;
import com.proxyblob.dto.Agent;
import com.proxyblob.dto.AgentCreationResult;
import com.proxyblob.dto.ParseResult;
import com.proxyblob.protocol.CryptoUtil;
import com.proxyblob.proxy.socks.SocksHandler;
import com.proxyblob.transport.BlobTransport;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.proxyblob.util.Constants.ErrConnectionStringError;
import static com.proxyblob.util.Constants.ErrContainerNotFound;
import static com.proxyblob.util.Constants.ErrContextCanceled;
import static com.proxyblob.util.Constants.ErrInfoBlobError;
import static com.proxyblob.util.Constants.ErrNoConnectionString;
import static com.proxyblob.util.Constants.InfoBlobName;
import static com.proxyblob.util.Constants.InfoKey;
import static com.proxyblob.util.Constants.RequestBlobName;
import static com.proxyblob.util.Constants.ResponseBlobName;
import static com.proxyblob.util.Constants.Success;

public class AgentUtil {

    public static AgentCreationResult createAgent(AppContext context, String connString) {
        ParseResult parsed = parseConnectionString(connString);
        if (parsed.getErrorCode() != Success) {
            return AgentCreationResult.builder()
                    .agent(null)
                    .status(parsed.getErrorCode())
                    .build();
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

            Agent agent = Agent.builder()
                    .containerClient(containerClient)
                    .handler(handler)
                    .build();

            return AgentCreationResult.builder()
                    .agent(agent)
                    .status(Success)
                    .build();
        } catch (Exception e) {
            return AgentCreationResult.builder()
                    .agent(null)
                    .status(ErrConnectionStringError)
                    .build();
        }
    }

    public static int start(AppContext context, Agent agent) {
        int result = writeInfoBlob(agent, context);
        if (result != Success) {
            stop(agent);
            return ErrContainerNotFound;
        }

        context.getGeneralExecutor().submit(() -> healthCheck(context, agent));

        agent.getHandler().start(StringUtils.EMPTY);

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

    private static void stop(Agent agent) {
        agent.getHandler().stop();
    }

    private static void healthCheck(AppContext context, Agent agent) {
        ScheduledExecutorService scheduler = context.getScheduler();
        Runnable task = () -> {
            if (context.isStopped()) {
                if (!scheduler.isShutdown()) {
                    scheduler.shutdown();
                }
                return;
            }

            try {
                BlockBlobClient blob = agent.getContainerClient()
                        .getBlobClient(InfoBlobName)
                        .getBlockBlobClient();

                blob.getProperties();

            } catch (BlobStorageException e) {
                String code = e.getErrorCode().toString();
                if (BlobErrorCode.CONTAINER_NOT_FOUND.toString().equals(code)
                        || BlobErrorCode.CONTAINER_BEING_DELETED.toString().equals(code)) {
                    stop(agent);
                }
            } catch (Exception e) {
                //TODO что то прописать
            }
        };

        scheduler.scheduleAtFixedRate(task, 0, 30, TimeUnit.SECONDS);
    }

    private static int writeInfoBlob(Agent agent, AppContext context) {
        try {
            String info = getCurrentInfo();
            byte[] encrypted = CryptoUtil.xor(info.getBytes(StandardCharsets.UTF_8), InfoKey);

            BlockBlobClient blob = agent.getContainerClient().getBlobClient(InfoBlobName).getBlockBlobClient();

            BlockBlobSimpleUploadOptions options = new BlockBlobSimpleUploadOptions(
                    new ByteArrayInputStream(encrypted), encrypted.length)
                    .setHeaders(new BlobHttpHeaders().setContentType("text/plain"));

            blob.uploadWithResponse(options, null, Context.NONE);
            return Success;
        } catch (BlobStorageException e) {
            String code = e.getErrorCode().toString();
            if (BlobErrorCode.CONTAINER_NOT_FOUND.toString().equals(code)
                    || BlobErrorCode.CONTAINER_BEING_DELETED.toString().equals(code)) {
                return ErrContainerNotFound;
            }
            return ErrInfoBlobError;
        } catch (Exception e) {
            if (context.isStopped()) {
                return ErrContextCanceled;
            }
            return ErrInfoBlobError;
        }
    }

    private static ParseResult parseConnectionString(String connString) {
        if (connString == null || connString.isEmpty()) {
            return ParseResult.builder()
                    .storageUrl(null)
                    .containerId(null)
                    .sasToken(null)
                    .errorCode(ErrNoConnectionString)
                    .build();
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
            return ParseResult.builder()
                    .storageUrl(storageUrl)
                    .containerId(containerId)
                    .sasToken(query)
                    .errorCode(Success)
                    .build();
        } catch (Exception e) {
            return error();
        }
    }

    private static ParseResult error() {
        return ParseResult.builder()
                .storageUrl(null)
                .containerId(null)
                .sasToken(null)
                .errorCode(ErrConnectionStringError)
                .build();
    }

    private static String getCurrentInfo() {
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

