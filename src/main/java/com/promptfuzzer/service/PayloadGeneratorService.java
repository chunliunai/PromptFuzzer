package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class PayloadGeneratorService {

    @Value("${promptfuzzer.dashscope.api-key}")
    private String apiKey;

    @Value("${promptfuzzer.dashscope.model:qwen-turbo}")
    private String model;

    private static final String DEFAULT_TARGET_CONTEXT = "目标是一个 AI 应用，具体系统配置未知";
    private static final int DEFAULT_GENERATE_COUNT = 3;

    private String generateTemplate;
    private final Map<String, String> goalDescriptions = new ConcurrentHashMap<>();
    private final Map<String, String> techniqueDescriptions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void loadResources() {
        generateTemplate = loadFile("prompts/attack/generate.txt");
        loadDirectory("prompts/attack/goals/", goalDescriptions);
        loadDirectory("prompts/attack/techniques/", techniqueDescriptions);
        log.info("PayloadGeneratorService loaded: {} goals, {} techniques",
                goalDescriptions.size(), techniqueDescriptions.size());
    }

    private String loadFile(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                log.error("File not found: {}", path);
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to load file: {}", path, e);
            return "";
        }
    }

    private void loadDirectory(String pattern, Map<String, String> target) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:" + pattern + "*.txt");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) continue;
                String key = filename.replace(".txt", "");
                try (InputStream is = resource.getInputStream()) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    target.put(key, content);
                    log.info("Loaded attack resource: {}{}", pattern, filename);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load directory: {}", pattern, e);
        }
    }

    public List<String> generate(String goalId, String techniqueId, String attackContext, int count) {
        String goalDesc = goalDescriptions.get(goalId);
        String techniqueDesc = techniqueDescriptions.get(techniqueId);

        if (goalDesc == null) {
            log.warn("Goal not found: {}", goalId);
            return Collections.emptyList();
        }
        if (techniqueDesc == null) {
            log.warn("Technique not found: {}", techniqueId);
            return Collections.emptyList();
        }

        String targetContext = (attackContext != null && !attackContext.isBlank())
                ? "攻击场景已知：\n" + attackContext
                : DEFAULT_TARGET_CONTEXT;

        int actualCount = count > 0 ? count : DEFAULT_GENERATE_COUNT;

        String prompt = generateTemplate
                .replace("{{GOAL_DESCRIPTION}}", goalDesc)
                .replace("{{TECHNIQUE_DESCRIPTION}}", techniqueDesc)
                .replace("{{TARGET_CONTEXT}}", targetContext)
                .replace("{{COUNT}}", String.valueOf(actualCount));

        try {
            String raw = callDashScope(prompt);
            List<String> result = parsePayloads(raw);
            if (!result.isEmpty()) return result;
            log.warn("Empty payloads on first attempt, retrying goal={} technique={}", goalId, techniqueId);
            raw = callDashScope(prompt);
            return parsePayloads(raw);
        } catch (Exception e) {
            log.warn("First attempt failed for goal={} technique={}, retrying...", goalId, techniqueId);
            try {
                String raw = callDashScope(prompt);
                return parsePayloads(raw);
            } catch (Exception e2) {
                log.error("Payload generation failed after retry for goal={} technique={}", goalId, techniqueId, e2);
                return Collections.emptyList();
            }
        }
    }

    public Set<String> getAvailableGoalIds() {
        return Collections.unmodifiableSet(goalDescriptions.keySet());
    }

    public Set<String> getAvailableTechniqueIds() {
        return Collections.unmodifiableSet(techniqueDescriptions.keySet());
    }

    private List<String> parsePayloads(String raw) throws Exception {
        JsonNode root = objectMapper.readTree(raw);
        String text = root.path("output").path("text").asText("");

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("No JSON found in generation response");
            return Collections.emptyList();
        }

        JsonNode result = objectMapper.readTree(text.substring(start, end + 1));
        JsonNode payloadsNode = result.path("payloads");

        List<String> payloads = new ArrayList<>();
        if (payloadsNode.isArray()) {
            for (JsonNode node : payloadsNode) {
                String p = node.asText("").trim();
                if (!p.isEmpty()) payloads.add(p);
            }
        }
        return payloads;
    }

    private String callDashScope(String prompt) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", new Object[]{message});

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);

        String requestBody = objectMapper.writeValueAsString(body);

        URL url = new URL("https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }

        try (java.util.Scanner scanner = new java.util.Scanner(conn.getInputStream(), StandardCharsets.UTF_8)) {
            return scanner.useDelimiter("\\A").next();
        }
    }
}
