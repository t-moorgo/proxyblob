package com.proxyblob.config;

public class LoggingConfig {

    public static void configureLogging() {
        // Установка дефолтного уровня логирования, если он не задан
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
        }

        // Настройка формата лога в консоль (если используешь SimpleLogger)
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss");
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
        System.setProperty("org.slf4j.simpleLogger.levelInBrackets", "true");
    }
}
