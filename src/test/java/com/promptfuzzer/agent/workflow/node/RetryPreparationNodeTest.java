package com.promptfuzzer.agent.workflow.node;

import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.agent.workflow.WorkflowNodeResult;
import com.promptfuzzer.agent.workflow.WorkflowRoute;
import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.service.AgentWorkflowTraceRecorder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetryPreparationNodeTest {

    @Test
    void browserRetryResetsChatBeforePlanning() throws Exception {
        ExecuteTargetNode executeTargetNode = mock(ExecuteTargetNode.class);
        StrategyPlanNode strategyPlanNode = mock(StrategyPlanNode.class);
        AgentWorkflowTraceRecorder traceRecorder = mock(AgentWorkflowTraceRecorder.class);
        RetryPreparationNode node =
                new RetryPreparationNode(executeTargetNode, strategyPlanNode, traceRecorder);

        Task task = new Task();
        AgentSession session = new AgentSession();
        AgentWorkflowContext context = AgentWorkflowContext.builder()
                .task(task)
                .session(session)
                .availableTechniques(List.of("none"))
                .browserMode(true)
                .build();
        AgentWorkflowState state = AgentWorkflowState.builder()
                .phase("ATTACK")
                .retryIndex(1)
                .turnIndex(0)
                .targetChatIndex(2)
                .maxTurns(4)
                .strategyPlan("old")
                .build();

        ExecuteTargetNode.BrowserActionResult reset = new ExecuteTargetNode.BrowserActionResult();
        reset.setSuccess(true);
        reset.setTargetChatIndex(3);
        when(executeTargetNode.newChat(context, state, 99L, 2)).thenReturn(reset);
        when(strategyPlanNode.execute(eq(context), any(), eq("RETRY_REPLAN"),
                eq(List.of("none")), eq(4), eq("intel"), eq("old"),
                eq("recent"), eq("reason")))
                .thenReturn(WorkflowNodeResult.continueWith("new"));

        WorkflowNodeResult<RetryPreparationNode.RetryPreparationOutput> result =
                node.execute(context, state, 99L, List.of("none"),
                        "intel", "recent", "reason");

        assertEquals(WorkflowRoute.CONTINUE, result.getRoute());
        assertEquals("new", result.getOutput().getStrategyPlan());
        assertEquals(3, result.getOutput().getTargetChatIndex());
        verify(strategyPlanNode).execute(eq(context),
                org.mockito.ArgumentMatchers.argThat(next -> next.getTargetChatIndex() == 3),
                eq("RETRY_REPLAN"), eq(List.of("none")), eq(4),
                eq("intel"), eq("old"), eq("recent"), eq("reason"));
    }
}
