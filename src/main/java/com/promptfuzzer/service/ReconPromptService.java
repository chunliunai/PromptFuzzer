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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

@Service
@RequiredArgsConstructor
public class ReconPromptService {

    @Value("${promptfuzzer.dashscope.api-key}")
    private String apiKey;

    @Value("${promptfuzzer.dashscope.model:qwen-plus}")
    private String model;

    private final ObjectMapper objectMapper;

    private String openCapabilityDiscoveryTemplate;
    private String capabilityVerifyTemplate;
    private String coveragePlanTemplate;
    private String coverageGapAnalysisTemplate;
    private String evidenceSummaryTemplate;
    private String applicationSurfaceSynthesisTemplate;

    @PostConstruct
    public void loadPrompts() {
        openCapabilityDiscoveryTemplate =
                loadFile("prompts/recon/open_capability_discovery.txt");
        capabilityVerifyTemplate = loadFile("prompts/recon/capability_verify.txt");
        coveragePlanTemplate = loadFile("prompts/recon/coverage_plan.txt");
        coverageGapAnalysisTemplate =
                loadFile("prompts/recon/coverage_gap_analysis.txt");
        evidenceSummaryTemplate = loadFile("prompts/recon/evidence_summary.txt");
        applicationSurfaceSynthesisTemplate =
                loadFile("prompts/recon/application_surface_synthesis.txt");
    }

    public String generateOpenCapabilityDiscoveryMessage(
            String reconContext,
            String pageContent) throws Exception {
        String prompt = openCapabilityDiscoveryTemplate
                .replace("{{RECON_CONTEXT}}",
                        value(reconContext, "（未提供）"))
                .replace("{{PAGE_CONTENT}}",
                        truncate(value(
                                pageContent, "（未读取到页面内容）"), 8000));
        JsonNode result = callForJson(prompt, 0.2);
        String message = result.path("message").asText("").trim();
        if (message.isBlank()) {
            throw new IllegalStateException(
                    "Open capability discovery returned no message");
        }
        return message;
    }

    public JsonNode planCoverage(String reconContext,
                                 String externalIntelligence,
                                 String pageContent,
                                 String openDiscovery,
                                 int maxDiscoveryMessages) throws Exception {
        String prompt = coveragePlanTemplate
                .replace("{{RECON_CONTEXT}}", value(reconContext, "（未提供）"))
                .replace("{{EXTERNAL_INTELLIGENCE}}",
                        value(externalIntelligence, "（未提供）"))
                .replace("{{PAGE_CONTENT}}",
                        truncate(value(pageContent, "（未读取到页面内容）"), 8000))
                .replace("{{OPEN_DISCOVERY}}",
                        truncate(value(openDiscovery, "（未执行或未获得回复）"), 16000))
                .replace("{{MAX_DISCOVERY_MESSAGES}}",
                        String.valueOf(Math.max(1, maxDiscoveryMessages)));
        return callForJson(prompt, 0.1);
    }

    public List<String> discoveryMessages(JsonNode coveragePlan, int maxCount) {
        return stringArray(coveragePlan.path("discoveryMessages"), maxCount);
    }

    public JsonNode analyzeCoverageGaps(String reconContext,
                                        JsonNode coveragePlan,
                                        String discoveryRecords,
                                        int maxGapFollowups) throws Exception {
        String prompt = coverageGapAnalysisTemplate
                .replace("{{RECON_CONTEXT}}", value(reconContext, "（未提供）"))
                .replace("{{COVERAGE_PLAN}}",
                        truncate(value(json(coveragePlan), "{}"), 12000))
                .replace("{{DISCOVERY_RECORDS}}",
                        truncate(value(discoveryRecords, "[]"), 20000))
                .replace("{{MAX_GAP_FOLLOWUPS}}",
                        String.valueOf(Math.max(0, maxGapFollowups)));
        return callForJson(prompt, 0.1);
    }

    public List<String> gapFollowupMessages(JsonNode gapAnalysis, int maxCount) {
        return stringArray(gapAnalysis.path("followupMessages"), maxCount);
    }

    public List<String> generateCapabilityVerificationMessages(String reconContext,
                                                               String pageContent,
                                                               String discoveryResponse,
                                                               int maxCount) throws Exception {
        if (maxCount <= 0) {
            return List.of();
        }
        String prompt = capabilityVerifyTemplate
                .replace("{{RECON_CONTEXT}}", value(reconContext, "（未提供）"))
                .replace("{{PAGE_CONTENT}}", truncate(value(pageContent, "（未读取到页面内容）"), 6000))
                .replace("{{CAPABILITY_EVIDENCE}}",
                        truncate(value(discoveryResponse, "（目标未回复）"), 20000))
                .replace("{{MAX_COUNT}}", String.valueOf(Math.max(0, maxCount)));
        JsonNode result = callForJson(prompt, 0.2);
        List<String> messages = new ArrayList<>();
        JsonNode messagesNode = result.path("messages");
        if (messagesNode.isArray()) {
            for (JsonNode node : messagesNode) {
                String message = node.asText("").trim();
                if (!message.isBlank()) {
                    messages.add(message);
                }
                if (messages.size() >= maxCount) {
                    break;
                }
            }
        }
        return messages;
    }

    public JsonNode summarizeEvidence(String reconContext,
                                      String externalIntelligence,
                                      String pageContent,
                                      String coverageMatrix,
                                      String traceRecordsJson) throws Exception {
        String prompt = evidenceSummaryTemplate
                .replace("{{RECON_CONTEXT}}", value(reconContext, "（未提供）"))
                .replace("{{EXTERNAL_INTELLIGENCE}}",
                        value(externalIntelligence, "（未提供）"))
                .replace("{{PAGE_CONTENT}}",
                        truncate(value(pageContent, "（未读取到页面内容）"), 8000))
                .replace("{{COVERAGE_MATRIX}}",
                        truncate(value(coverageMatrix, "[]"), 16000))
                .replace("{{TRACE_RECORDS}}",
                        truncate(value(traceRecordsJson, "[]"), 32000));
        return callForJson(prompt, 0.1);
    }

    public JsonNode synthesizeApplicationSurfaces(
            JsonNode evidenceSummary,
            String coverageMatrix) throws Exception {
        String prompt = applicationSurfaceSynthesisTemplate
                .replace("{{EVIDENCE_SUMMARY}}",
                        truncate(value(json(evidenceSummary), "{}"), 32000))
                .replace("{{COVERAGE_MATRIX}}",
                        truncate(value(coverageMatrix, "[]"), 16000));
        return callForJson(prompt, 0.1);
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
            throw new IllegalStateException("Failed to load RECON prompt: " + path, e);
        }
    }

    private String extractJson(String value) {
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("RECON prompt returned invalid JSON: " + truncate(value, 500));
        }
        return value.substring(start, end + 1);
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private List<String> stringArray(JsonNode node, int maxCount) {
        List<String> values = new ArrayList<>();
        if (!node.isArray() || maxCount <= 0) {
            return values;
        }
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
            if (values.size() >= maxCount) {
                break;
            }
        }
        return values;
    }

    private String json(JsonNode node) {
        return node == null || node.isMissingNode() ? "{}" : node.toString();
    }

    private String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }
}
