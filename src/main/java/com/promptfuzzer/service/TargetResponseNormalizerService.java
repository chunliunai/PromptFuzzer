package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TargetResponseNormalizerService {

    private final JudgeService judgeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NormalizedTargetResponse normalize(String rawResponse,
                                              String contentType,
                                              String executionChannel,
                                              boolean allowAiFallback) {
        String raw = rawResponse != null ? rawResponse : "";
        String body = stripHttpStatusLine(raw);
        String protocol = detectProtocol(contentType, body, executionChannel);
        Extraction extraction = switch (protocol) {
            case "SSE" -> new Extraction(extractSseText(body), "SSE_DATA_FIELDS");
            case "JSON" -> new Extraction(extractJsonText(body), "JSON_LIKELY_FIELDS");
            case "DOM_TEXT" -> new Extraction(body.trim(), "DOM_TEXT_PASSTHROUGH");
            default -> new Extraction(body.trim(), "TEXT_PASSTHROUGH");
        };

        boolean usedAi = false;
        String extractedText = extraction.text();
        String extractionMethod = extraction.method();
        if (allowAiFallback && shouldUseAiFallback(protocol, extractedText, body)) {
            JudgeService.JudgeResult aiResult = judgeService.extractOnly(raw);
            if (aiResult.getExtractedText() != null && !aiResult.getExtractedText().isBlank()) {
                extractedText = aiResult.getExtractedText();
                extractionMethod = "AI_EXTRACT_ONLY";
                usedAi = true;
            }
        }

        double confidence = confidence(protocol, extractionMethod, extractedText);
        return new NormalizedTargetResponse(
                raw,
                body,
                extractedText != null ? extractedText : "",
                executionChannel,
                protocol,
                extractionMethod,
                confidence,
                usedAi,
                raw.length());
    }

    private String stripHttpStatusLine(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.startsWith("HTTP ")) {
            int newline = raw.indexOf('\n');
            return newline >= 0 ? raw.substring(newline + 1).trim() : "";
        }
        return raw.trim();
    }

    private String detectProtocol(String contentType, String body, String executionChannel) {
        if ("BROWSER".equalsIgnoreCase(executionChannel) || "BROWSER_DOM".equalsIgnoreCase(executionChannel)) {
            return "DOM_TEXT";
        }
        String lowerType = lower(contentType);
        String trimmed = body != null ? body.stripLeading() : "";
        if (lowerType.contains("event-stream") || trimmed.lines().anyMatch(line -> line.startsWith("data:"))) {
            return "SSE";
        }
        if (lowerType.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return "JSON";
        }
        return "TEXT";
    }

    private boolean shouldUseAiFallback(String protocol, String extractedText, String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        if (extractedText == null || extractedText.isBlank()) {
            return true;
        }
        return ("JSON".equals(protocol) || "SSE".equals(protocol))
                && extractedText.trim().equals(body.trim());
    }

    private String extractSseText(String body) {
        StringBuilder extracted = new StringBuilder();
        body.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim())
                .filter(data -> !data.isBlank() && !"[DONE]".equalsIgnoreCase(data))
                .forEach(data -> {
                    String text = data.startsWith("{") ? extractJsonText(data) : data;
                    if (text != null && !text.isBlank()) {
                        if (extracted.length() > 0) {
                            extracted.append("\n");
                        }
                        extracted.append(text.trim());
                    }
                });
        return extracted.toString();
    }

    private String extractJsonText(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            List<String> fragments = new ArrayList<>();
            collectLikelyResponseText(root, fragments);
            if (!fragments.isEmpty()) {
                return String.join("\n", fragments);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private void collectLikelyResponseText(JsonNode node, List<String> fragments) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = lower(entry.getKey());
                JsonNode value = entry.getValue();
                if (value.isTextual() && isLikelyResponseTextKey(key)) {
                    String text = value.asText();
                    if (!text.isBlank()) {
                        fragments.add(text);
                    }
                } else {
                    collectLikelyResponseText(value, fragments);
                }
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectLikelyResponseText(child, fragments));
        }
    }

    private boolean isLikelyResponseTextKey(String key) {
        return "content".equals(key)
                || "text".equals(key)
                || "answer".equals(key)
                || "response".equals(key)
                || "message".equals(key)
                || "delta".equals(key);
    }

    private double confidence(String protocol, String extractionMethod, String extractedText) {
        if (extractedText == null || extractedText.isBlank()) {
            return 0.0;
        }
        if ("AI_EXTRACT_ONLY".equals(extractionMethod)) {
            return 0.8;
        }
        return switch (protocol) {
            case "SSE", "JSON" -> 0.9;
            case "DOM_TEXT" -> 0.75;
            default -> 0.6;
        };
    }

    private String lower(String value) {
        return value != null ? value.toLowerCase(Locale.ROOT) : "";
    }

    private record Extraction(String text, String method) {
    }

    public record NormalizedTargetResponse(
            String rawResponse,
            String responseBody,
            String extractedText,
            String executionChannel,
            String responseProtocol,
            String extractionMethod,
            double extractionConfidence,
            boolean aiFallbackUsed,
            int rawResponseChars) {
    }
}
