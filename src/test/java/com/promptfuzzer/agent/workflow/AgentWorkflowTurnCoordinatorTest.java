package com.promptfuzzer.agent.workflow;

import com.promptfuzzer.agent.workflow.node.AnalyzeNode;
import com.promptfuzzer.agent.workflow.node.ExecuteTargetNode;
import com.promptfuzzer.agent.workflow.node.GeneratePayloadNode;
import com.promptfuzzer.agent.workflow.node.JudgeOrExtractNode;
import com.promptfuzzer.agent.workflow.node.MemoryDeltaNode;
import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.service.AgentPlannerService;
import com.promptfuzzer.service.JudgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWorkflowTurnCoordinatorTest {

    private AnalyzeNode analyzeNode;
    private GeneratePayloadNode generatePayloadNode;
    private ExecuteTargetNode executeTargetNode;
    private JudgeOrExtractNode judgeOrExtractNode;
    private MemoryDeltaNode memoryDeltaNode;
    private AgentWorkflowTurnCoordinator coordinator;

    @BeforeEach
    void setUp() {
        analyzeNode = mock(AnalyzeNode.class);
        generatePayloadNode = mock(GeneratePayloadNode.class);
        executeTargetNode = mock(ExecuteTargetNode.class);
        judgeOrExtractNode = mock(JudgeOrExtractNode.class);
        memoryDeltaNode = mock(MemoryDeltaNode.class);
        coordinator = new AgentWorkflowTurnCoordinator(
                analyzeNode, generatePayloadNode, executeTargetNode, judgeOrExtractNode, memoryDeltaNode);
    }

    @Test
    void browserAttackSuccessReturnsSuccessRouteWithoutMemoryDelta() throws Exception {
        AgentPlannerService.StrategyAnalysis analysis = analysis("SEND_MESSAGE", false);
        when(analyzeNode.execute(any(), any(), eq("[]"), eq("intel"), eq(1), eq(4), eq(0)))
                .thenReturn(analysis);
        when(generatePayloadNode.execute(any(), any(), eq(analysis), eq("none"),
                eq("[]"), any(), eq(34L))).thenReturn("message");
        when(executeTargetNode.sendBrowserMessage(any(), any(), eq(99L), eq("message")))
                .thenReturn("response");
        JudgeService.JudgeResult judgeResult = judge("SUCCESS", "confirmed", "value");
        when(judgeOrExtractNode.execute(any(), any(), eq("judge"))).thenReturn(judgeResult);

        WorkflowNodeResult<AgentWorkflowTurnOutput> result =
                coordinator.execute(request(true, "ATTACK"));

        assertEquals(WorkflowRoute.SUCCESS, result.getRoute());
        assertEquals("SUCCESS", result.getOutput().getTurnRecord().get("verdict"));
        assertEquals("value", result.getOutput().getTurnRecord().get("extractedText"));
        verify(memoryDeltaNode, never()).execute(any(), any());
    }

    @Test
    void httpBuildExecutesExtractionAndMemoryDelta() throws Exception {
        AgentPlannerService.StrategyAnalysis analysis = analysis(null, false);
        when(analyzeNode.execute(any(), any(), eq("[]"), eq("intel"), eq(1), eq(4), eq(0)))
                .thenReturn(analysis);
        when(generatePayloadNode.execute(any(), any(), eq(analysis), eq("none"),
                eq("[]"), any(), eq(34L))).thenReturn("message");
        when(executeTargetNode.sendHttp(any(), any(), eq("template"), eq("message")))
                .thenReturn("raw");
        when(judgeOrExtractNode.execute(any(), any(), eq("extractOnly")))
                .thenReturn(judge(null, null, "extracted"));
        when(memoryDeltaNode.execute(any(), any())).thenReturn("next-signals");

        WorkflowNodeResult<AgentWorkflowTurnOutput> result =
                coordinator.execute(request(false, "BUILD"));

        assertEquals(WorkflowRoute.CONTINUE, result.getRoute());
        assertEquals("extracted", result.getOutput().getTurnRecord().get("extractedText"));
        assertEquals("next-signals", result.getOutput().getAttackSignals());
        assertNull(result.getOutput().getTurnRecord().get("verdict"));
    }

    @Test
    void plannerStopReturnsStopRouteWithoutDispatch() throws Exception {
        AgentPlannerService.StrategyAnalysis analysis = analysis("SEND_MESSAGE", true);
        analysis.setStopReason("done");
        when(analyzeNode.execute(any(), any(), eq("[]"), eq("intel"), eq(1), eq(4), eq(0)))
                .thenReturn(analysis);

        WorkflowNodeResult<AgentWorkflowTurnOutput> result =
                coordinator.execute(request(true, "BUILD"));

        assertEquals(WorkflowRoute.STOP, result.getRoute());
        assertEquals("done", result.getEvidence());
        verify(generatePayloadNode, never()).execute(any(), any(), any(), any(), any(), any(), any());
        verify(executeTargetNode, never()).sendBrowserMessage(any(), any(), any(), any());
    }

    private AgentWorkflowTurnRequest request(boolean browser, String phase) {
        Task task = new Task();
        task.setId(12L);
        AgentSession session = new AgentSession();
        session.setId(34L);
        return AgentWorkflowTurnRequest.builder()
                .context(AgentWorkflowContext.builder()
                        .task(task)
                        .session(session)
                        .availableTechniques(List.of("none"))
                        .browserMode(browser)
                        .build())
                .state(AgentWorkflowState.builder()
                        .taskId(12L)
                        .agentSessionId(34L)
                        .goal("prompt_leak")
                        .phase(phase)
                        .turnIndex(1)
                        .retryIndex(0)
                        .targetChatIndex(2)
                        .maxTurns(4)
                        .attackContext("context")
                        .attackSignals("signals")
                        .build())
                .conversationJson("[]")
                .intelligenceLog("intel")
                .turnRecords(new ArrayList<>())
                .browserSessionId(99L)
                .rawRequestTemplate("template")
                .build();
    }

    private AgentPlannerService.StrategyAnalysis analysis(String actionType, boolean shouldStop) {
        AgentPlannerService.StrategyAnalysis analysis = new AgentPlannerService.StrategyAnalysis();
        analysis.setAnalysis("analysis");
        analysis.setChosenTechnique("none");
        analysis.setObservations("observations");
        analysis.setShouldStop(shouldStop);
        if (actionType != null) {
            AgentPlannerService.ActionInfo action = new AgentPlannerService.ActionInfo();
            action.setType(actionType);
            action.setTechnique("none");
            analysis.setAction(action);
        }
        return analysis;
    }

    private JudgeService.JudgeResult judge(String verdict, String evidence, String extracted) {
        JudgeService.JudgeResult result = new JudgeService.JudgeResult();
        result.setVerdict(verdict);
        result.setEvidence(evidence);
        result.setExtractedText(extracted);
        return result;
    }
}
