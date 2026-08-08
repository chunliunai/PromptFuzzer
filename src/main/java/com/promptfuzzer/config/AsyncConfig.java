package com.promptfuzzer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Value("${promptfuzzer.executor.core-pool-size:5}")
    private int corePoolSize;

    @Value("${promptfuzzer.executor.max-pool-size:10}")
    private int maxPoolSize;

    @Value("${promptfuzzer.executor.queue-capacity:100}")
    private int queueCapacity;

    @Bean(name = "scanExecutor")
    public Executor scanExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("scan-");
        executor.initialize();
        return executor;
    }
}
