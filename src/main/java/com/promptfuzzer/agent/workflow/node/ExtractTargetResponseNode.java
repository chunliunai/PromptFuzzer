package com.promptfuzzer.agent.workflow.node;

import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowNode;
import com.promptfuzzer.agent.workflow.AgentWorkflowNodes;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.service.AgentWorkflowTraceRecorder;
import com.promptfuzzer.service.TargetResponseNormalizerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ExtractTargetResponseNode implements AgentWorkflowNode {

    private final TargetResponseNormalizerService normalizerService;
    private final AgentWorkflowTraceRecorder traceRecorder;

    @Override
    public String name() {
        return AgentWorkflowNodes.EXTRACT_TARGET_RESPONSE;
    }

    public TargetResponseNormalizerService.NormalizedTargetResponse execute(AgentWorkflowContext context,
                                                                           AgentWorkflowState state,
                                                                           String rawResponse,
                                                                           String contentType,
                                                                           String executionChannel,
                                                                           boolean allowAiFallback) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> input = mapOf(
                "executionChannel", executionChannel,
                "contentType", contentType,
                "rawResponseChars", length(rawResponse),
                "aiFallbackAllowed", allowAiFallback);
        try {
            TargetResponseNormalizerService.NormalizedTargetResponse normalized =
                    normalizerService.normalize(rawResponse, contentType, executionChannel, allowAiFallback);
            traceRecorder.success(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input,
                    mapOf("responseProtocol", normalized.responseProtocol(),
                            "extractionMethod", normalized.extractionMethod(),
                            "extractionConfidence", normalized.extractionConfidence(),
                            "aiFallbackUsed", normalized.aiFallbackUsed(),
                            "extractedTextChars", length(normalized.extractedText()),
                            "extractedTextPreview", preview(normalized.extractedText(), 500)),
                    null, null, startedAt);
            return normalized;
        } catch (Exception e) {
            traceRecorder.error(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input, null, "UNCERTAIN", "响应标准化异常", e, startedAt);
            throw e;
        }
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
}
