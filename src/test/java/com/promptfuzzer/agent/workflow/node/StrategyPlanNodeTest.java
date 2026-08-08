package com.promptfuzzer.agent.workflow.node;

import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowNodes;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.agent.workflow.WorkflowNodeResult;
import com.promptfuzzer.agent.workflow.WorkflowRoute;
import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.service.AgentPlannerService;
import com.promptfuzzer.service.AgentWorkflowTraceRecorder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategyPlanNodeTest {

    @Test
    void delegatesPlanningWithExplicitBudgetAndReturnsStateUpdate() throws Exception {
        AgentPlannerService plannerService = mock(AgentPlannerService.class);
        AgentWorkflowTraceRecorder traceRecorder = mock(AgentWorkflowTraceRecorder.class);
        StrategyPlanNode node = new StrategyPlanNode(plannerService, traceRecorder);

        Task task = new Task();
        task.setId(12L);
        AgentSession session = new AgentSession();
        session.setId(34L);
        session.setSessionIndex(1);
        AgentWorkflowContext context = AgentWorkflowContext.builder()
                .task(task)
                .session(session)
                .availableTechniques(List.of("none"))
                .browserMode(false)
                .build();
        AgentWorkflowState state = AgentWorkflowState.builder()
                .goal("prompt_leak")
                .phase("BUILD")
                .retryIndex(0)
                .turnIndex(0)
                .targetChatIndex(2)
                .attackContext("context")
                .targetIntelligenceMemory("memory")
                .attackSignals("signals")
                .build();

        when(plannerService.planStrategy(
                eq("context"), eq("prompt_leak"), eq(List.of("none")), eq(6),
                eq("intel"), eq("BUILD"), eq("memory"), eq("previous"),
                eq("signals"), eq("recent"), eq("reason")))
                .thenReturn("{\"strategyVersion\":1}");

        WorkflowNodeResult<String> result = node.execute(
                context, state, "INITIAL_PLAN", List.of("none"), 6,
                "intel", "previous", "recent", "reason");

        assertEquals(AgentWorkflowNodes.STRATEGY_PLAN, node.name());
        assertEquals(WorkflowRoute.CONTINUE, result.getRoute());
        assertEquals("{\"strategyVersion\":1}", result.getOutput());
        assertEquals(result.getOutput(), result.getStateUpdates().get("strategyPlan"));
        verify(traceRecorder).success(
                eq(task), eq(session), eq(AgentWorkflowNodes.STRATEGY_PLAN), eq("BUILD"),
                eq(0), eq(0), eq(2), any(), any(), eq(null), eq(null), any(Long.class));
    }
}
