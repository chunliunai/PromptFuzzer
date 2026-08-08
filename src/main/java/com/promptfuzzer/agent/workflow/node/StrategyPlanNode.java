package com.promptfuzzer.agent.workflow.node;

import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowNode;
import com.promptfuzzer.agent.workflow.AgentWorkflowNodes;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.agent.workflow.WorkflowNodeResult;
import com.promptfuzzer.service.AgentPlannerService;
import com.promptfuzzer.service.AgentWorkflowTraceRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class StrategyPlanNode implements AgentWorkflowNode {

    private final AgentPlannerService plannerService;
    private final AgentWorkflowTraceRecorder traceRecorder;

    @Override
    public String name() {
        return AgentWorkflowNodes.STRATEGY_PLAN;
    }

    public WorkflowNodeResult<String> execute(AgentWorkflowContext context,
                                              AgentWorkflowState state,
                                              String trigger,
                                              List<String> availableTechniques,
                                              int planningTurnBudget,
                                              String intelligenceLog,
                                              String previousStrategyPlan,
                                              String recentPayloads,
                                              String replanReason) throws Exception {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> input = mapOf(
                "trigger", trigger,
                "goal", state.getGoal(),
                "planningTurnBudget", planningTurnBudget,
                "recentPayloads", recentPayloads,
                "replanReason", replanReason
        );
        try {
            String strategyPlan = plannerService.planStrategy(
                    state.getAttackContext(),
                    state.getGoal(),
                    availableTechniques,
                    planningTurnBudget,
                    intelligenceLog,
                    state.getPhase(),
                    state.getTargetIntelligenceMemory(),
                    previousStrategyPlan,
                    state.getAttackSignals(),
                    recentPayloads,
                    replanReason);
            traceRecorder.success(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input,
                    mapOf("strategyPlanChars", strategyPlan != null ? strategyPlan.length() : 0),
                    null, null, startedAt);
            return WorkflowNodeResult.<String>continueWith(strategyPlan).toBuilder()
                    .stateUpdates(mapOf("strategyPlan", strategyPlan))
                    .build();
        } catch (Exception e) {
            traceRecorder.error(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input, null, null, "StrategyPlan 执行异常", e, startedAt);
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
