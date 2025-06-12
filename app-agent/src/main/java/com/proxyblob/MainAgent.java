package com.proxyblob;

import com.proxyblob.context.AppContext;
import com.proxyblob.dto.AgentCreationResult;

import java.util.concurrent.atomic.AtomicReference;

import static com.proxyblob.Agent.ErrNoConnectionString;
import static com.proxyblob.Agent.Success;

public class MainAgent {

    // Аналог переменной var ConnString string
    private static final AtomicReference<String> connStringRef = new AtomicReference<>("");

    public static void main(String[] args) {
        // Парсим аргументы, ищем -c <connString>
        for (int i = 0; i < args.length - 1; i++) {
            if ("-c".equals(args[i])) {
                connStringRef.set(args[i + 1]);
                break;
            }
        }

        String connString = connStringRef.get();
        if (connString == null || connString.isEmpty()) {
            System.exit(ErrNoConnectionString);
        }

        // Инициализируем контекст
        AppContext context = new AppContext();

        // Обработка сигналов SIGINT/SIGTERM (Ctrl+C)
        Runtime.getRuntime().addShutdownHook(new Thread(context::stop));

        // Создание агента
        Agent temp = new Agent(null, null); // нужен только чтобы вызвать .create()
        AgentCreationResult result = temp.create(context, connString);

        if (result.getStatus() != Success) {
            System.exit(result.getStatus());
        }

        // Запуск агента
        int exitCode = result.getAgent().start(context);
        System.exit(exitCode);
    }
}
