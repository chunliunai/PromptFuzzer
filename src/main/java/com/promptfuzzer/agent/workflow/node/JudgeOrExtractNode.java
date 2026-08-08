package com.promptfuzzer.agent.workflow.node;

import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowNode;
import com.promptfuzzer.agent.workflow.AgentWorkflowNodes;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.service.AgentWorkflowTraceRecorder;
import com.promptfuzzer.service.JudgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JudgeOrExtractNode implements AgentWorkflowNode {

    private final JudgeService judgeService;
    private final AgentWorkflowTraceRecorder traceRecorder;

    @Override
    public String name() {
        return AgentWorkflowNodes.JUDGE_OR_EXTRACT;
    }

    public JudgeService.JudgeResult execute(AgentWorkflowContext context,
                                            AgentWorkflowState state,
                                            String mode) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> input = mapOf(
                "mode", mode,
                "goal", state.getGoal(),
                "rawResponseChars", length(state.getRawResponse())
        );
        try {
            JudgeService.JudgeResult result;
            if ("judge".equals(mode)) {
                result = judgeService.judge(state.getRawResponse(), state.getGoal(), state.getAttackContext());
                traceRecorder.success(context.getTask(), context.getSession(), name(), state.getPhase(),
                        state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                        input,
                        mapOf("verdict", result.getVerdict(),
                                "evidence", result.getEvidence(),
                                "extractedTextChars", length(result.getExtractedText())),
                        result.getVerdict(), result.getEvidence(), startedAt);
            } else {
                result = judgeService.extractOnly(state.getRawResponse());
                traceRecorder.success(context.getTask(), context.getSession(), name(), state.getPhase(),
                        state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                        input,
                        mapOf("extractedTextChars", length(result.getExtractedText()),
                                "extractedTextPreview", preview(result.getExtractedText(), 500)),
                        null, null, startedAt);
            }
            return result;
        } catch (Exception e) {
            String evidence = "judge".equals(mode) ? "Judge 调用异常" : "文本提取异常";
            traceRecorder.error(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input, null, "UNCERTAIN", evidence, e, startedAt);
            throw e;
        }
    }

    public void recordBrowserExtract(AgentWorkflowContext context, AgentWorkflowState state) {
        long startedAt = System.currentTimeMillis();
        traceRecorder.success(context.getTask(), context.getSession(), name(), state.getPhase(),
                state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                mapOf("mode", "browserRawExtract", "rawResponseChars", length(state.getRawResponse())),
                mapOf("extractedTextChars", length(state.getRawResponse()),
                        "extractedTextPreview", preview(state.getRawResponse(), 500)),
                null, null, startedAt);
    }

    private int length(String value) {
        return value != null ? value.length() : 0;
    }

    private String preview(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }
}
