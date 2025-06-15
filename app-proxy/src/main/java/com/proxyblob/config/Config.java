package com.proxyblob.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;

@Getter
@NoArgsConstructor
public class Config {

    @JsonProperty("storage_account_name")
    private String storageAccountName;

    @JsonProperty("storage_account_key")
    private String storageAccountKey;

    @JsonProperty("storage_url")
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
        ObjectMapper mapper = new ObjectMapper();

        try {
            if (configPath == null || configPath.isEmpty()) {
                try (InputStream is = Config.class.getClassLoader().getResourceAsStream("config.json")) {
                    if (is == null) {
                        throw new IllegalStateException("Default config.json not found in resources");
                    }
                    Config config = mapper.readValue(is, Config.class);
                    config.validate();
                    return config;
                }
            } else {
                File file = Paths.get(configPath).toAbsolutePath().toFile();
                if (!file.exists()) {
                    throw new IllegalStateException("Configuration file not found at: " + file.getAbsolutePath());
                }
                Config config = mapper.readValue(file, Config.class);
                config.validate();
                return config;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config", e);
        }
    }
}
