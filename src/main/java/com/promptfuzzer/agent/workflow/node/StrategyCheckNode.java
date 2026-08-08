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
public class StrategyCheckNode implements AgentWorkflowNode {

    private final AgentPlannerService plannerService;
    private final AgentWorkflowTraceRecorder traceRecorder;

    @Override
    public String name() {
        return AgentWorkflowNodes.STRATEGY_CHECK;
    }

    public AgentPlannerService.StrategyCheckResult execute(AgentWorkflowContext context,
                                                           AgentWorkflowState state,
                                                           String payload1,
                                                           String payload2,
                                                           String payload3,
                                                           String response1,
                                                           String response2,
                                                           String response3,
                                                           String weaponHistory) throws Exception {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> input = mapOf(
                "payload1", payload1,
                "payload2", payload2,
                "payload3", payload3,
                "weaponHistory", weaponHistory
        );
        try {
            AgentPlannerService.StrategyCheckResult result = plannerService.checkStrategy(
                    state.getGoal(), payload1, payload2, payload3,
                    response1, response2, response3, weaponHistory, state.getStrategyPlan());
            traceRecorder.success(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input,
                    mapOf("status", result.getStatus(),
                            "routeHealth", result.getRouteHealth(),
                            "goalProgress", result.getGoalProgress(),
                            "shouldReplan", result.isShouldReplan(),
                            "shouldChangeFamily", result.isShouldChangeFamily(),
                            "reason", result.getReason()),
                    null, result.getReason(), startedAt);
            return result;
        } catch (Exception e) {
            traceRecorder.error(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input, null, null, null, e, startedAt);
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
