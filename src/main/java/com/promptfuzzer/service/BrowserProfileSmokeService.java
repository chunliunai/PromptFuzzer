package com.promptfuzzer.service;

import com.promptfuzzer.config.BrowserTargetConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class BrowserProfileSmokeService {

    private static final List<String> ERROR_MARKERS = List.of(
            "harness error",
            "json parse error",
            "模型调用失败",
            "运行错误",
            "re-login required",
            "token unavailable");

    private final BrowserService browserService;
    private final BrowserTargetProfileService profileService;
    private final AtomicLong sessionIds = new AtomicLong(-1_000_000L);

    public SmokeResult run(String targetType, String prompt) {
        String normalizedPrompt = prompt == null || prompt.isBlank()
                ? "请只回复：对话正常"
                : prompt.trim();
        long sessionId = sessionIds.decrementAndGet();
        try {
            BrowserTargetConfig config = profileService.configFromProfile(targetType);
            browserService.openSession(sessionId, config);
            BrowserService.BrowserMessageResult result =
                    browserService.sendMessageWithCapture(sessionId, normalizedPrompt);
            String responseText = result != null ? result.responseText() : null;
            int exchangeCount = result != null && result.trafficExchanges() != null
                    ? result.trafficExchanges().size()
                    : 0;
            String failureReason = validateResponse(responseText);
            return new SmokeResult(
                    targetType,
                    failureReason == null,
                    responseText,
                    exchangeCount,
                    failureReason);
        } catch (Exception e) {
            return new SmokeResult(targetType, false, null, 0, e.getMessage());
        } finally {
            browserService.closeSession(sessionId);
        }
    }

    private String validateResponse(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            return "Target returned no assistant response";
        }
        String normalized = responseText.toLowerCase(Locale.ROOT);
        return ERROR_MARKERS.stream()
                .filter(normalized::contains)
                .findFirst()
                .map(marker -> "Target response contains error marker: " + marker)
                .orElse(null);
    }

    public record SmokeResult(
            String targetType,
            boolean success,
            String responseText,
            int trafficExchangeCount,
            String failureReason) {
    }
}
