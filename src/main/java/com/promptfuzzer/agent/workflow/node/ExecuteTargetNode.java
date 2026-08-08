package com.promptfuzzer.agent.workflow.node;

import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowNode;
import com.promptfuzzer.agent.workflow.AgentWorkflowNodes;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.service.AgentWorkflowTraceRecorder;
import com.promptfuzzer.service.BrowserService;
import com.promptfuzzer.service.HttpRequestService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ExecuteTargetNode implements AgentWorkflowNode {

    private final HttpRequestService httpRequestService;
    private final BrowserService browserService;
    private final AgentWorkflowTraceRecorder traceRecorder;

    @Override
    public String name() {
        return AgentWorkflowNodes.EXECUTE_TARGET;
    }

    public String sendHttp(AgentWorkflowContext context,
                           AgentWorkflowState state,
                           String rawRequestTemplate,
                           String message) throws Exception {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> input = mapOf("mode", "HTTP_TEMPLATE", "messageChars", length(message));
        try {
            String rawResponse = httpRequestService.send(rawRequestTemplate, message);
            traceRecorder.success(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input,
                    mapOf("rawResponseChars", length(rawResponse),
                            "rawResponsePreview", preview(rawResponse, 500)),
                    null, null, startedAt);
            return rawResponse;
        } catch (Exception e) {
            traceRecorder.error(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input, null, "UNCERTAIN", "HTTP 发送异常", e, startedAt);
            throw e;
        }
    }

    public String sendBrowserMessage(AgentWorkflowContext context,
                                     AgentWorkflowState state,
                                     Long browserSessionId,
                                     String message) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> input = mapOf("mode", "BROWSER_SEND_MESSAGE", "messageChars", length(message));
        try {
            String rawResponse = browserService.sendMessage(browserSessionId, message);
            if (rawResponse == null) {
                throw new RuntimeException("Browser returned null response");
            }
            traceRecorder.success(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input,
                    mapOf("rawResponseChars", rawResponse.length(),
                            "rawResponsePreview", preview(rawResponse, 500)),
                    null, null, startedAt);
            return rawResponse;
        } catch (Exception e) {
            traceRecorder.error(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input, null, "UNCERTAIN", "浏览器发送异常", e, startedAt);
            throw e;
        }
    }

    public BrowserActionResult newChat(AgentWorkflowContext context,
                                       AgentWorkflowState state,
                                       Long browserSessionId,
                                       int currentTargetChatIndex) {
        long startedAt = System.currentTimeMillis();
        boolean success = browserService.newChat(browserSessionId);
        int nextTargetChatIndex = success ? currentTargetChatIndex + 1 : currentTargetChatIndex;
        traceRecorder.success(context.getTask(), context.getSession(), name(), state.getPhase(),
                state.getRetryIndex(), state.getTurnIndex(), nextTargetChatIndex,
                mapOf("mode", "BROWSER_NEW_CHAT"),
                mapOf("success", success, "targetChatIndex", nextTargetChatIndex),
                null, success ? "已重置对话" : "重置失败", startedAt);
        BrowserActionResult result = new BrowserActionResult();
        result.setSuccess(success);
        result.setTargetChatIndex(nextTargetChatIndex);
        result.setEvidence(success ? "已重置对话" : "重置失败");
        return result;
    }

    public String readPage(AgentWorkflowContext context,
                           AgentWorkflowState state,
                           Long browserSessionId,
                           String selector) {
        long startedAt = System.currentTimeMillis();
        String pageContent = browserService.readPage(browserSessionId, selector);
        traceRecorder.success(context.getTask(), context.getSession(), name(), state.getPhase(),
                state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                mapOf("mode", "BROWSER_READ_PAGE", "selector", selector),
                mapOf("pageContentChars", length(pageContent),
                        "pageContentPreview", preview(pageContent, 500)),
                null, null, startedAt);
        return pageContent;
    }

    public BrowserActionResult click(AgentWorkflowContext context,
                                     AgentWorkflowState state,
                                     Long browserSessionId,
                                     String selector) {
        long startedAt = System.currentTimeMillis();
        boolean success = selector != null && browserService.click(browserSessionId, selector);
        String evidence = success ? "已点击 " + selector : "点击失败: selector为空";
        traceRecorder.success(context.getTask(), context.getSession(), name(), state.getPhase(),
                state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                mapOf("mode", "BROWSER_CLICK", "selector", selector),
                mapOf("success", success),
                null, evidence, startedAt);
        BrowserActionResult result = new BrowserActionResult();
        result.setSuccess(success);
        result.setTargetChatIndex(state.getTargetChatIndex() != null ? state.getTargetChatIndex() : 0);
        result.setEvidence(evidence);
        return result;
    }

    private int length(String value) {
        return value != null ? value.length() : 0;
    }

    private String preview(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > maxChars ? oneLine.substring(0, maxChars) + "..." : oneLine;
    }

    private Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }

    @Data
    public static class BrowserActionResult {
        private boolean success;
        private int targetChatIndex;
        private String evidence;
    }
}
