package com.proxyblob.config;

import lombok.Data;

@Data
public class Config {

    private String storageAccountName;
    private String storageAccountKey;

    public String getStorageUrl() {
        return String.format("https://%s.blob.core.windows.net", storageAccountName);
    }
}
