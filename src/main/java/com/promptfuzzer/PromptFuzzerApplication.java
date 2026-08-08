package com.promptfuzzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class PromptFuzzerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PromptFuzzerApplication.class, args);
    }
}
