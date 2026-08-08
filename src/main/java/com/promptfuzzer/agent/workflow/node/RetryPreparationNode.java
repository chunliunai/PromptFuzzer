package com.promptfuzzer.agent.workflow.node;

import com.promptfuzzer.agent.workflow.AgentTurnBudget;
import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowNode;
import com.promptfuzzer.agent.workflow.AgentWorkflowNodes;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.agent.workflow.WorkflowNodeResult;
import com.promptfuzzer.agent.workflow.WorkflowRoute;
import com.promptfuzzer.service.AgentWorkflowTraceRecorder;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class RetryPreparationNode implements AgentWorkflowNode {

    private final ExecuteTargetNode executeTargetNode;
    private final StrategyPlanNode strategyPlanNode;
    private final AgentWorkflowTraceRecorder traceRecorder;

    @Override
    public String name() {
        return AgentWorkflowNodes.RETRY_PREPARATION;
    }

    public WorkflowNodeResult<RetryPreparationOutput> execute(
            AgentWorkflowContext context,
            AgentWorkflowState state,
            Long browserSessionId,
            List<String> availableTechniques,
            String intelligenceLog,
            String recentPayloads,
            String replanReason) throws Exception {
        long startedAt = System.currentTimeMillis();
        int targetChatIndex = value(state.getTargetChatIndex());
        Map<String, Object> input = mapOf(
                "retryIndex", state.getRetryIndex(),
                "browserMode", context.isBrowserMode(),
                "targetChatIndex", targetChatIndex,
                "recentPayloads", recentPayloads,
                "replanReason", replanReason
        );
        try {
            if (context.isBrowserMode()) {
                ExecuteTargetNode.BrowserActionResult resetResult = executeTargetNode.newChat(
                        context, state, browserSessionId, targetChatIndex);
                targetChatIndex = resetResult.getTargetChatIndex();
            }

            AgentWorkflowState planningState = state.toBuilder()
                    .targetChatIndex(targetChatIndex)
                    .build();
            WorkflowNodeResult<String> planResult = strategyPlanNode.execute(
                    context,
                    planningState,
                    "RETRY_REPLAN",
                    availableTechniques,
                    AgentTurnBudget.beforeFirstTurn(value(state.getMaxTurns()))
                            .remainingTurnsIncludingCurrent(),
                    intelligenceLog,
                    state.getStrategyPlan(),
                    recentPayloads,
                    replanReason);

            RetryPreparationOutput output = RetryPreparationOutput.builder()
                    .strategyPlan(planResult.getOutput())
                    .targetChatIndex(targetChatIndex)
                    .build();
            traceRecorder.success(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), 0, targetChatIndex,
                    input,
                    mapOf("strategyPlanChars", length(output.getStrategyPlan()),
                            "targetChatIndex", targetChatIndex),
                    null, null, startedAt);
            return WorkflowNodeResult.<RetryPreparationOutput>builder()
                    .output(output)
                    .route(WorkflowRoute.CONTINUE)
                    .stateUpdates(mapOf(
                            "strategyPlan", output.getStrategyPlan(),
                            "targetChatIndex", targetChatIndex))
                    .build();
        } catch (Exception e) {
            traceRecorder.error(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), 0, targetChatIndex,
                    input, null, null, "Retry 准备阶段异常", e, startedAt);
            throw e;
        }
    }

    private int value(Integer value) {
        return value != null ? value : 0;
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

    @Value
    @Builder
    public static class RetryPreparationOutput {
        String strategyPlan;
        int targetChatIndex;
    }
}
