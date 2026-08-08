package com.promptfuzzer.agent.workflow.node;

import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.agent.workflow.WorkflowNodeResult;
import com.promptfuzzer.agent.workflow.WorkflowRoute;
import com.promptfuzzer.dto.ReconConfig;
import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.service.AgentPlannerService;
import com.promptfuzzer.service.AgentWorkflowTraceRecorder;
import com.promptfuzzer.service.JudgeService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndependentReconNodeTest {

    @Test
    void skipTargetProbeSummarizesMemoryAndPlansBuildStrategy() throws Exception {
        ExecuteTargetNode executeTargetNode = mock(ExecuteTargetNode.class);
        AgentPlannerService plannerService = mock(AgentPlannerService.class);
        JudgeService judgeService = mock(JudgeService.class);
        StrategyPlanNode strategyPlanNode = mock(StrategyPlanNode.class);
        AgentWorkflowTraceRecorder traceRecorder = mock(AgentWorkflowTraceRecorder.class);
        IndependentReconNode node = new IndependentReconNode(
                executeTargetNode, plannerService, judgeService, strategyPlanNode, traceRecorder);

        Task task = new Task();
        task.setId(11L);
        AgentSession session = new AgentSession();
        session.setId(22L);
        session.setIntelligenceLog("intel");
        AgentWorkflowContext context = AgentWorkflowContext.builder()
                .task(task)
                .session(session)
                .availableTechniques(List.of("none"))
                .browserMode(false)
                .build();
        AgentWorkflowState state = AgentWorkflowState.builder()
                .phase("RECON")
                .goal("goal")
                .attackContext("context")
                .targetIntelligenceMemory("old-memory")
                .strategyPlan("old-plan")
                .targetChatIndex(0)
                .maxTurns(4)
                .retryIndex(0)
                .turnIndex(0)
                .build();
        ReconConfig config = new ReconConfig();
        config.setMode("SKIP_TARGET_PROBE");
        config.setMaxChats(0);
        config.setTurnsPerChat(0);
        config.setMaxTotalTurns(0);
        config.setCleanBeforeAttack(false);

        when(plannerService.summarizeReconMemory(eq("context"), eq("goal"),
                eq("old-memory"), eq("[]"))).thenReturn("new-memory");
        when(strategyPlanNode.execute(eq(context), any(), eq("POST_INDEPENDENT_RECON"),
                eq(List.of("none")), eq(4), eq("intel"), eq("old-plan"),
                any(), any())).thenReturn(WorkflowNodeResult.continueWith("new-plan"));

        WorkflowNodeResult<IndependentReconNode.IndependentReconOutput> result =
                node.execute(context, state, 99L, config);

        assertEquals(WorkflowRoute.NEXT_PHASE, result.getRoute());
        assertEquals("new-memory", result.getOutput().getTargetMemory());
        assertEquals("new-plan", result.getOutput().getStrategyPlan());
        assertEquals(0, result.getOutput().getTurnRecords().size());
        assertEquals("BUILD", result.getStateUpdates().get("phase"));
        verify(strategyPlanNode).execute(eq(context),
                org.mockito.ArgumentMatchers.argThat(next ->
                        "BUILD".equals(next.getPhase())
                                && "new-memory".equals(next.getTargetIntelligenceMemory())),
                eq("POST_INDEPENDENT_RECON"), eq(List.of("none")), eq(4),
                eq("intel"), eq("old-plan"), any(), any());
    }
}
