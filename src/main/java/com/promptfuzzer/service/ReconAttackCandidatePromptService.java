package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

@Service
@RequiredArgsConstructor
public class ReconAttackCandidatePromptService {

    @Value("${promptfuzzer.dashscope.api-key}")
    private String apiKey;

    @Value("${promptfuzzer.dashscope.model:qwen-plus}")
    private String model;

    private final ObjectMapper objectMapper;

    private String attackUnitSynthesisTemplate;
    private String candidateGenerateTemplate;

    @PostConstruct
    public void loadPrompts() {
        attackUnitSynthesisTemplate = loadFile(
                "prompts/recon_attack_candidate/attack_unit_synthesis.txt");
        candidateGenerateTemplate = loadFile(
                "prompts/recon_attack_candidate/candidate_generate.txt");
    }

    public JsonNode synthesizeAttackUnits(
            String reconResult,
            String applicationSurfaces,
            int maxAttackUnits) throws Exception {
        String prompt = attackUnitSynthesisTemplate
                .replace("{{RECON_RESULT}}",
                        truncate(value(reconResult, "{}"), 16000))
                .replace("{{APPLICATION_SURFACES}}",
                        truncate(value(applicationSurfaces, "[]"), 16000))
                .replace("{{MAX_ATTACK_UNITS}}",
                        String.valueOf(maxAttackUnits));
        return callForJson(prompt, 0.1);
    }

    public JsonNode generateCandidates(
            String reconResult,
            String attackUnits,
            String availableGoals,
            String goalPreferences,
            int candidateCountPerAttackUnit) throws Exception {
        String prompt = candidateGenerateTemplate
                .replace("{{RECON_RESULT}}", truncate(value(reconResult, "{}"), 16000))
                .replace("{{ATTACK_UNITS}}",
                        truncate(value(attackUnits, "[]"), 16000))
                .replace("{{AVAILABLE_GOALS}}", value(availableGoals, "[]"))
                .replace("{{GOAL_PREFERENCES}}", value(goalPreferences, "[]"))
                .replace("{{CANDIDATE_COUNT_PER_ATTACK_UNIT}}",
                        String.valueOf(candidateCountPerAttackUnit));
        return callForJson(prompt, 0.2);
    }

    private JsonNode callForJson(String prompt, double temperature) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DASHSCOPE_API_KEY is not configured");
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", new Object[]{message});

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("temperature", temperature);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);

        HttpURLConnection connection = (HttpURLConnection) new URL(
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation")
                .openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000);

        try (OutputStream output = connection.getOutputStream()) {
            output.write(objectMapper.writeValueAsBytes(body));
        }

        int status = connection.getResponseCode();
        InputStream responseStream = status >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        String response;
        try (Scanner scanner = new Scanner(responseStream, StandardCharsets.UTF_8)) {
            response = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
        }
        if (status >= 400) {
            throw new IllegalStateException("DashScope HTTP " + status + ": " + response);
        }

        String text = objectMapper.readTree(response).path("output").path("text").asText("");
        return objectMapper.readTree(extractJson(text));
    }

    private String loadFile(String path) {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load RECON attack candidate prompt: " + path, e);
        }
    }

    private String extractJson(String value) {
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException(
                    "RECON attack candidate prompt returned invalid JSON: "
                            + truncate(value, 500));
        }
        return value.substring(start, end + 1);
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }
}
