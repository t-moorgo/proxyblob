package com.proxyblob.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

@Getter
@NoArgsConstructor
public class Config {

    private String storageAccountName;
    private String storageAccountKey;
    private String storageURL;

    public void validate() {
        if (storageAccountName == null || storageAccountName.isEmpty()) {
            throw new IllegalArgumentException("storage_account_name is required");
        }
        if (storageAccountKey == null || storageAccountKey.isEmpty()) {
            throw new IllegalArgumentException("storage_account_key is required");
        }
    }

    public static Config load(String configPath) {
        if (configPath == null || configPath.isEmpty()) {
            configPath = "config.json";
        }

        File file = Paths.get(configPath).toAbsolutePath().toFile();

        if (!file.exists()) {
            throw new IllegalStateException("Configuration file not found at: " + file.getAbsolutePath());
        }

        ObjectMapper mapper = new ObjectMapper();
        try {
            Config config = mapper.readValue(file, Config.class);
            config.validate();
            return config;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read or parse config file: " + file.getAbsolutePath(), e);
        }
    }
}
