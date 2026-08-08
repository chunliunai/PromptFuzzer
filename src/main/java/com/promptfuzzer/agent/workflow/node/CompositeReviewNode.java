package com.promptfuzzer.agent.workflow.node;

import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowNode;
import com.promptfuzzer.agent.workflow.AgentWorkflowNodes;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.service.AgentWorkflowTraceRecorder;
import com.promptfuzzer.service.JudgeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CompositeReviewNode implements AgentWorkflowNode {

    private static final int COMPOSITE_MAX_CHARS = 8000;

    private final JudgeService judgeService;
    private final AgentWorkflowTraceRecorder traceRecorder;

    @Override
    public String name() {
        return AgentWorkflowNodes.COMPOSITE_REVIEW;
    }

    public ReviewResult execute(AgentWorkflowContext context,
                                AgentWorkflowState state,
                                List<Map<String, Object>> turnRecords,
                                String scope) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> input = mapOf("scope", scope,
                "turns", turnRecords != null ? turnRecords.size() : 0);
        try {
            String compositeText = buildCompositeText(turnRecords, "attempt".equals(scope));
            if (compositeText.isBlank()) {
                ReviewResult empty = new ReviewResult();
                empty.setCompositeChars(0);
                empty.setEmpty(true);
                traceRecorder.skipped(context.getTask(), context.getSession(), name(), state.getPhase(),
                        state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                        input, "No extracted text available for composite review", startedAt);
                return empty;
            }

            JudgeService.JudgeResult judgeResult = judgeService.judge(
                    compositeText, state.getGoal(), state.getAttackContext());
            ReviewResult result = new ReviewResult();
            result.setJudgeResult(judgeResult);
            result.setCompositeChars(compositeText.length());
            result.setEmpty(false);
            traceRecorder.success(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    mapOf("scope", scope,
                            "turns", turnRecords != null ? turnRecords.size() : 0,
                            "compositeChars", compositeText.length()),
                    mapOf("verdict", judgeResult.getVerdict(),
                            "evidence", judgeResult.getEvidence()),
                    judgeResult.getVerdict(), judgeResult.getEvidence(), startedAt);
            return result;
        } catch (Exception e) {
            traceRecorder.error(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input, null, null, null, e, startedAt);
            throw e;
        }
    }

    private String buildCompositeText(List<Map<String, Object>> turnRecords, boolean nonAttackOnly) {
        if (turnRecords == null || turnRecords.isEmpty()) {
            return "";
        }
        StringBuilder composite = new StringBuilder();
        for (Map<String, Object> turn : turnRecords) {
            Object phase = turn.get("phase");
            Object extracted = turn.get("extractedText");
            if (extracted == null || extracted.toString().isBlank()) {
                continue;
            }
            if (nonAttackOnly && "ATTACK".equals(phase)) {
                continue;
            }
            composite.append("=== Turn ").append(turn.get("turn"))
                    .append(" [").append(phase).append("] ===\n")
                    .append(extracted).append("\n\n");
        }
        String compositeText = composite.toString();
        if (compositeText.length() > COMPOSITE_MAX_CHARS) {
            return compositeText.substring(0, COMPOSITE_MAX_CHARS);
        }
        return compositeText;
    }

    private Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }

    @Data
    public static class ReviewResult {
        private JudgeService.JudgeResult judgeResult;
        private int compositeChars;
        private boolean empty;
    }
}
