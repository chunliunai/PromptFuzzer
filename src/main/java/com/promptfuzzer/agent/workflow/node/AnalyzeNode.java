package com.promptfuzzer.agent.workflow.node;

import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowNode;
import com.promptfuzzer.agent.workflow.AgentWorkflowNodes;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.service.AgentPlannerService;
import com.promptfuzzer.service.AgentWorkflowTraceRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AnalyzeNode implements AgentWorkflowNode {

    private final AgentPlannerService plannerService;
    private final AgentWorkflowTraceRecorder traceRecorder;

    @Override
    public String name() {
        return AgentWorkflowNodes.ANALYZE;
    }

    public AgentPlannerService.StrategyAnalysis execute(AgentWorkflowContext context,
                                                        AgentWorkflowState state,
                                                        String conversationJson,
                                                        String intelligenceLog,
                                                        int currentTurn,
                                                        int maxTurns,
                                                        int historyTurns) throws Exception {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> input = mapOf(
                "goal", state.getGoal(),
                "isBrowser", context.isBrowserMode(),
                "historyTurns", historyTurns,
                "availableTechniques", context.getAvailableTechniques()
        );
        try {
            AgentPlannerService.StrategyAnalysis analysis = plannerService.analyze(
                    conversationJson,
                    state.getGoal(),
                    context.getAvailableTechniques(),
                    state.getAttackContext(),
                    currentTurn,
                    maxTurns,
                    state.getPhase(),
                    intelligenceLog,
                    state.getStrategyPlan(),
                    context.isBrowserMode(),
                    state.getTargetIntelligenceMemory(),
                    state.getBuildMemory(),
                    state.getAttackSignals(),
                    state.getTargetChatIndex() != null ? state.getTargetChatIndex() : 0);
            traceRecorder.success(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input,
                    mapOf("chosenTechnique", analysis.getChosenTechnique(),
                            "phaseTransition", analysis.isPhaseTransition(),
                            "strategyInvalidated", analysis.isStrategyInvalidated(),
                            "shouldStop", analysis.isShouldStop(),
                            "stopReason", analysis.getStopReason(),
                            "action", analysis.getAction()),
                    null, analysis.getObservations(), startedAt);
            return analysis;
        } catch (Exception e) {
            traceRecorder.error(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input, null, "UNCERTAIN", "Planner 分析阶段异常", e, startedAt);
            throw e;
        }
    }

    private Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }
}
