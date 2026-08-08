package com.promptfuzzer.agent.workflow.node;

import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowNode;
import com.promptfuzzer.agent.workflow.AgentWorkflowNodes;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.service.AgentPlannerService;
import com.promptfuzzer.service.AgentWorkflowTraceRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryDeltaNode implements AgentWorkflowNode {

    private final AgentPlannerService plannerService;
    private final AgentWorkflowTraceRecorder traceRecorder;

    @Override
    public String name() {
        return AgentWorkflowNodes.MEMORY_DELTA;
    }

    public String execute(AgentWorkflowContext context, AgentWorkflowState state) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> input = mapOf(
                "verdict", state.getVerdict(),
                "evidence", state.getEvidence()
        );
        try {
            String delta = plannerService.extractMemoryDelta(
                    state.getGoal(),
                    state.getPhase(),
                    state.getTargetChatIndex() != null ? state.getTargetChatIndex() : 0,
                    state.getAttackContext(),
                    state.getTargetIntelligenceMemory(),
                    state.getBuildMemory(),
                    state.getMessage(),
                    state.getExtractedText(),
                    state.getVerdict(),
                    state.getEvidence());
            String nextAttackSignals = appendMemorySection(state.getAttackSignals(),
                    "Delta " + state.getPhase() + " T" + state.getTurnIndex(), delta);
            traceRecorder.success(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input,
                    mapOf("deltaChars", length(delta),
                            "deltaPreview", preview(delta, 500),
                            "attackSignalsChars", length(nextAttackSignals)),
                    state.getVerdict(), state.getEvidence(), startedAt);
            return nextAttackSignals;
        } catch (Exception e) {
            log.debug("AgentSession {} memory delta extraction failed: {}",
                    context.getSession() != null ? context.getSession().getId() : null, e.getMessage());
            traceRecorder.error(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input, null, state.getVerdict(), state.getEvidence(), e, startedAt);
            return state.getAttackSignals();
        }
    }

    private String appendMemorySection(String existing, String title, String content) {
        if (content == null || content.isBlank()) {
            return existing;
        }
        String base = existing != null ? existing : "";
        String merged = base + "\n\n## " + title + "\n" + content;
        int maxLen = 12000;
        if (merged.length() > maxLen) {
            return merged.substring(merged.length() - maxLen);
        }
        return merged;
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
