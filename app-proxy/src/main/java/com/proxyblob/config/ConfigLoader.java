package com.proxyblob.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;

import java.io.File;

@UtilityClass
public class ConfigLoader {
    public static Config load(String path) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(new File(path), Config.class);
    }
}
