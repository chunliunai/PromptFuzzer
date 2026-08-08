package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class JudgeService {

    @Value("${promptfuzzer.dashscope.api-key}")
    private String apiKey;

    @Value("${promptfuzzer.dashscope.model:qwen-turbo}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, String> promptCache = new ConcurrentHashMap<>();

    private static final Set<String> SUPPORTED_GOALS = Set.of(
            "prompt_leak", "credential_leak", "user_data_leak",
            "context_leak", "model_info", "prohibited_content", "fake_confirmation",
            "protected_value_extraction", "command_execution", "authorization_bypass"
    );
    private static final int EXTRACT_MAX_CHARS = 6000;
    private static final int JUDGE_MAX_CHARS = 3000;
    private static final int PREVIEW_MAX_CHARS = 500;

    @PostConstruct
    public void loadPrompts() {
        loadPrompt("extract", "prompts/judge/extract.txt");
        loadPrompt("judge_prompt_leak", "prompts/judge/judge_prompt_leak.txt");
        loadPrompt("judge_credential_leak", "prompts/judge/judge_credential_leak.txt");
        loadPrompt("judge_user_data_leak", "prompts/judge/judge_user_data_leak.txt");
        loadPrompt("judge_context_leak", "prompts/judge/judge_context_leak.txt");
        loadPrompt("judge_model_info", "prompts/judge/judge_model_info.txt");
        loadPrompt("judge_prohibited_content", "prompts/judge/judge_prohibited_content.txt");
        loadPrompt("judge_fake_confirmation", "prompts/judge/judge_fake_confirmation.txt");
        loadPrompt("judge_protected_value_extraction", "prompts/judge/judge_protected_value_extraction.txt");
        loadPrompt("judge_command_execution", "prompts/judge/judge_command_execution.txt");
        loadPrompt("judge_authorization_bypass", "prompts/judge/judge_authorization_bypass.txt");
    }

    private void loadPrompt(String key, String path) {
        try (InputStream is = new ClassPathResource(path).getInputStream()) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            promptCache.put(key, content);
            log.info("Loaded prompt file: {}", path);
        } catch (Exception e) {
            log.error("Failed to load prompt file: {}", path, e);
        }
    }

    /**
     * Extract-only mode for RECON/BUILD phases. Only extracts LLM reply text, no verdict.
     */
    public JudgeResult extractOnly(String rawResponse) {
        JudgeResult result = new JudgeResult();
        result.setVerdict(null); // no verdict in non-attack phases
        try {
            String extractedText = extractLlmReply(rawResponse);
            result.setExtractedText(extractedText);
            result.setExtractedTextLength(extractedText != null ? extractedText.length() : 0);
        } catch (Exception e) {
            log.warn("Extract-only failed", e);
            result.setExtractedText("");
            result.setExtractedTextLength(0);
        }
        return result;
    }

    public JudgeResult judge(String rawResponse, String goal, String attackContext) {
        JudgeResult fallback = new JudgeResult();
        fallback.setVerdict("UNCERTAIN");
        fallback.setEvidence("AI 判分未配置或调用失败");
        fallback.setExtractedText("");
        fallback.setRawResponse("");

        if (apiKey == null || apiKey.equals("sk-placeholder")) {
            log.warn("DashScope API key not configured, skipping judge");
            return fallback;
        }

        if (!SUPPORTED_GOALS.contains(goal)) {
            fallback.setEvidence("当前仅支持信息泄露类判定，操作类攻击暂不自动判定");
            return fallback;
        }

        try {
            String extractedText = extractLlmReply(rawResponse);
            fallback.setExtractedText(extractedText);

            if (extractedText == null || extractedText.isBlank()) {
                fallback.setEvidence("无法从响应中提取 LLM 回复文本");
                return fallback;
            }

            JudgeResult result = judgeByGoal(extractedText, goal, attackContext);
            result.setExtractedText(extractedText);
            result.setExtractedTextLength(extractedText.length());
            result.setExtractedTextPreview(extractedText.length() <= PREVIEW_MAX_CHARS
                    ? extractedText
                    : extractedText.substring(0, PREVIEW_MAX_CHARS) + "…");
            return result;

        } catch (Exception e) {
            log.error("Judge failed for goal={}", goal, e);
            return fallback;
        }
    }

    private String extractLlmReply(String rawResponse) throws Exception {
        String prompt = promptCache.get("extract");
        if (prompt == null) {
            throw new IllegalStateException("extract.txt prompt not loaded");
        }
        String truncatedRaw = rawResponse.length() <= EXTRACT_MAX_CHARS
                ? rawResponse
                : rawResponse.substring(0, EXTRACT_MAX_CHARS) + "…（响应过长已截断）";
        prompt = prompt.replace("{{RAW_RESPONSE}}", truncatedRaw);

        String dashscopeResponse = callDashScope(prompt);
        String innerText = parseDashScopeText(dashscopeResponse);
        String jsonStr = extractJson(innerText);
        JsonNode node = objectMapper.readTree(jsonStr);
        return node.path("extractedText").asText("");
    }

    private JudgeResult judgeByGoal(String extractedText, String goal, String attackContext) throws Exception {
        String promptKey = "judge_" + goal;
        String prompt = promptCache.get(promptKey);
        if (prompt == null) {
            log.warn("No judge prompt found for goal={}, using generic fallback", goal);
            JudgeResult r = new JudgeResult();
            r.setVerdict("UNCERTAIN");
            r.setEvidence("未找到对应 goal 的判定提示词文件");
            return r;
        }

        String attackContextFilled = (attackContext != null && !attackContext.isBlank())
                ? attackContext
                : "（未提供，盲测模式）";

        String truncated = extractedText.length() <= JUDGE_MAX_CHARS
                ? extractedText
                : extractedText.substring(0, JUDGE_MAX_CHARS) + "…（内容过长已截断）";

        prompt = prompt
                .replace("{{EXTRACTED_TEXT}}", truncated)
                .replace("{{ATTACK_CONTEXT}}", attackContextFilled);

        String dashscopeResponse = callDashScope(prompt);
        String innerText = parseDashScopeText(dashscopeResponse);
        String jsonStr = extractJson(innerText);
        JsonNode node = objectMapper.readTree(jsonStr);

        JudgeResult result = new JudgeResult();
        result.setVerdict(node.path("verdict").asText("UNCERTAIN").toUpperCase());
        result.setEvidence(node.path("evidence").asText(""));
        result.setRawResponse(dashscopeResponse);
        return result;
    }

    private String parseDashScopeText(String dashscopeResponse) throws Exception {
        JsonNode root = objectMapper.readTree(dashscopeResponse);
        return root.path("output").path("text").asText("");
    }

    private String callDashScope(String prompt) throws Exception {
        Map<String, Object> message = new java.util.LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("messages", new Object[]{message});

        Map<String, Object> body = new java.util.LinkedHashMap<>();
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
        conn.setReadTimeout(60000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }

        try (Scanner scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8)) {
            return scanner.useDelimiter("\\A").next();
        }
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    @Data
    public static class JudgeResult {
        private String verdict;
        private String evidence;
        private String extractedText;
        private String extractedTextPreview;
        private Integer extractedTextLength;
        private String rawResponse;
    }
}
