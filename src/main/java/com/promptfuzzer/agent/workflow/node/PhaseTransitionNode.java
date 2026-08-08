package com.promptfuzzer.agent.workflow.node;

import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowNode;
import com.promptfuzzer.agent.workflow.AgentWorkflowNodes;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.service.AgentWorkflowTraceRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PhaseTransitionNode implements AgentWorkflowNode {

    private static final String[] PHASES = {"RECON", "BUILD", "ATTACK"};

    private final AgentWorkflowTraceRecorder traceRecorder;

    @Override
    public String name() {
        return AgentWorkflowNodes.PHASE_TRANSITION;
    }

    public String execute(AgentWorkflowContext context,
                          AgentWorkflowState state,
                          int turnsInPhase,
                          String observations,
                          String targetMemory,
                          String buildMemory,
                          String strategyPlan) {
        long startedAt = System.currentTimeMillis();
        String previousPhase = state.getPhase();
        String nextPhase = nextPhase(previousPhase);
        traceRecorder.success(context.getTask(), context.getSession(), name(), previousPhase,
                state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                mapOf("fromPhase", previousPhase,
                        "turnsInPhase", turnsInPhase,
                        "observations", observations),
                mapOf("toPhase", nextPhase,
                        "targetMemoryChars", length(targetMemory),
                        "buildMemoryChars", length(buildMemory),
                        "strategyPlanChars", length(strategyPlan)),
                null, null, startedAt);
        return nextPhase;
    }

    private String nextPhase(String current) {
        for (int i = 0; i < PHASES.length - 1; i++) {
            if (PHASES[i].equals(current)) {
                return PHASES[i + 1];
            }
        }
        return current;
    }

    private int length(String value) {
        return value != null ? value.length() : 0;
    }

    private Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }
}
