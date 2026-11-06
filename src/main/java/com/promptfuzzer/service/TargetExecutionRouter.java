package com.promptfuzzer.service;

import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.dto.ReconConfig;
import com.promptfuzzer.entity.ReplayProfile;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Routes browser-backed target execution through HTTP replay when a same-chat
 * ReplayProfile is available. Browser remains the fallback and capture source.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TargetExecutionRouter {

    private final BrowserService browserService;
    private final ReplayProfileService replayProfileService;

    public TargetExecutionResult send(AgentWorkflowContext context,
                                      AgentWorkflowState state,
                                      Long browserSessionId,
                                      String message) {
        return send(context, state, browserSessionId, message,
                ReconConfig.TargetExecutionMode.AUTO);
    }

    public TargetExecutionResult send(AgentWorkflowContext context,
                                      AgentWorkflowState state,
                                      Long browserSessionId,
                                      String message,
                                      ReconConfig.TargetExecutionMode executionMode) {
        ReconConfig.TargetExecutionMode normalizedMode = executionMode != null
                ? executionMode
                : ReconConfig.TargetExecutionMode.AUTO;
        if (normalizedMode == ReconConfig.TargetExecutionMode.BROWSER_ONLY) {
            return sendViaBrowser(
                    context, state, browserSessionId, message, false);
        }

        ReplayProfileService.ReplayExecutionResult replayResult =
                tryHttpReplay(context, state, browserSessionId, message);
        if (replayResult != null && replayResult.success()) {
            return TargetExecutionResult.httpReplay(replayResult);
        }

        TargetExecutionResult browserResult = sendViaBrowser(
                context, state, browserSessionId, message, true);
        if (replayResult != null && !replayResult.success()) {
            browserResult.setFallbackType(replayResult.failureType());
            browserResult.setFallbackReason(replayResult.failureReason());
        }
        return browserResult;
    }

    private ReplayProfileService.ReplayExecutionResult tryHttpReplay(AgentWorkflowContext context,
                                                                     AgentWorkflowState state,
                                                                     Long browserSessionId,
                                                                     String message) {
        if (context == null || !context.isBrowserMode() || browserSessionId == null) {
            return null;
        }
        try {
            return replayProfileService.replayLatestCandidate(
                            context.getTask(), state, browserSessionId, message)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("HTTP replay routing skipped: {}", e.getMessage());
            return null;
        }
    }

    private TargetExecutionResult sendViaBrowser(AgentWorkflowContext context,
                                                 AgentWorkflowState state,
                                                 Long browserSessionId,
                                                 String message,
                                                 boolean observeReplayProfile) {
        BrowserService.BrowserMessageResult browserResult =
                browserService.sendMessageWithCapture(browserSessionId, message);
        String rawResponse = browserResult != null ? browserResult.responseText() : null;
        if (rawResponse == null) {
            throw new RuntimeException("Browser returned null response");
        }
        ReplayProfile replayProfile = observeReplayProfile
                ? observeReplayProfile(
                        context, state, message, browserResult.trafficExchanges())
                : null;
        return TargetExecutionResult.browser(rawResponse, replayProfile,
                browserResult.trafficExchanges());
    }

    private ReplayProfile observeReplayProfile(AgentWorkflowContext context,
                                               AgentWorkflowState state,
                                               String message,
                                               List<BrowserService.TrafficExchange> exchanges) {
        try {
            return replayProfileService.observeBrowserTraffic(context, state, message, exchanges)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Replay profile observation skipped: {}", e.getMessage());
            return null;
        }
    }

    @Data
    public static class TargetExecutionResult {
        private String rawResponse;
        private String extractedText;
          private String requestBody;
        private String executionChannel;
        private Long replayProfileId;
        private String replayProfileClassification;
        private String fallbackType;
        private String fallbackReason;
        private Integer statusCode;
        private String contentType;
        private List<BrowserService.TrafficExchange> trafficExchanges;

        static TargetExecutionResult browser(String rawResponse,
                                             ReplayProfile replayProfile,
                                             List<BrowserService.TrafficExchange> trafficExchanges) {
            TargetExecutionResult result = new TargetExecutionResult();
            result.setRawResponse(rawResponse);
              result.setRequestBody(browserRequestBody(trafficExchanges));
            result.setExecutionChannel("BROWSER");
            result.setTrafficExchanges(trafficExchanges);
            if (replayProfile != null) {
                result.setReplayProfileId(replayProfile.getId());
                result.setReplayProfileClassification(replayProfile.getClassification().name());
            }
            return result;
        }

        static TargetExecutionResult httpReplay(ReplayProfileService.ReplayExecutionResult replayResult) {
            TargetExecutionResult result = new TargetExecutionResult();
            result.setRawResponse(replayResult.rawResponse());
            result.setExtractedText(replayResult.extractedText());
              result.setRequestBody(formatReplayRequest(replayResult.requestMethod(),
                      replayResult.requestUrl(), replayResult.requestHeaders(), replayResult.requestBody()));
            result.setExecutionChannel("HTTP_REPLAY");
            result.setStatusCode(replayResult.statusCode());
            result.setContentType(replayResult.contentType());
            result.setFallbackType(replayResult.failureType());
            result.setFallbackReason(replayResult.failureReason());
            if (replayResult.profile() != null) {
                result.setReplayProfileId(replayResult.profile().getId());
                result.setReplayProfileClassification(replayResult.profile().getClassification().name());
            }
            return result;
        }

          private static String browserRequestBody(List<BrowserService.TrafficExchange> trafficExchanges) {
              if (trafficExchanges == null || trafficExchanges.isEmpty()) {
                  return null;
              }
              BrowserService.TrafficExchange exchange = trafficExchanges.get(0);
              StringBuilder sb = new StringBuilder();
              if (exchange.method() != null || exchange.url() != null) {
                  sb.append(value(exchange.method())).append(' ').append(value(exchange.url())).append('\n');
              }
              if (exchange.requestHeadersRaw() != null && !exchange.requestHeadersRaw().isBlank()) {
                  sb.append(exchange.requestHeadersRaw()).append('\n');
              }
              String body = exchange.requestBodyRaw() != null && !exchange.requestBodyRaw().isBlank()
                      ? exchange.requestBodyRaw()
                      : exchange.requestBodyPreview();
              if (body != null && !body.isBlank()) {
                  sb.append('\n').append(body);
              }
              return sb.isEmpty() ? null : sb.toString();
          }

          private static String formatReplayRequest(String method, String url,
                                                    Map<String, String> headers,
                                                    String body) {
              StringBuilder sb = new StringBuilder();
              if (method != null || url != null) {
                  sb.append(value(method)).append(' ').append(value(url)).append('\n');
              }
              if (headers != null && !headers.isEmpty()) {
                  headers.forEach((name, headerValue) ->
                          sb.append(name).append(": ").append(headerValue).append('\n'));
              }
              if (body != null && !body.isBlank()) {
                  sb.append('\n').append(body);
              }
              return sb.isEmpty() ? null : sb.toString();
          }

          private static String value(String text) {
              return text != null ? text : "";
          }
    }
}
