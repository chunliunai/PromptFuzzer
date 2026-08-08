package com.promptfuzzer.agent.workflow;

import com.promptfuzzer.service.AgentPlannerService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentWorkflowRouteDeciderTest {

    private final AgentWorkflowRouteDecider decider = new AgentWorkflowRouteDecider();

    @Test
    void deadStrategyStopsBeforeTurnExecution() {
        AgentPlannerService.StrategyCheckResult check = new AgentPlannerService.StrategyCheckResult();
        check.setStatus("DEAD");
        check.setReason("exhausted");

        WorkflowNodeResult<Void> result = decider.afterStrategyCheck(check);

        assertEquals(WorkflowRoute.STOP, result.getRoute());
        assertTrue((Boolean) result.getStateUpdates().get("strategyDead"));
    }

    @Test
    void staleStrategyRequestsReplan() {
        AgentPlannerService.StrategyCheckResult check = new AgentPlannerService.StrategyCheckResult();
        check.setStatus("STALE");

        assertEquals(WorkflowRoute.REPLAN, decider.afterStrategyCheck(check).getRoute());
    }

    @Test
    void invalidatedStrategyOnFinalTurnDoesNotReplan() {
        AgentPlannerService.StrategyAnalysis analysis = new AgentPlannerService.StrategyAnalysis();
        analysis.setStrategyInvalidated(true);

        WorkflowNodeResult<Void> result = decider.afterTurn(
                analysis, AgentTurnBudget.forExecutingTurn(4, 4),
                "ATTACK", 1, 2, "normal response");

        assertEquals(WorkflowRoute.CONTINUE, result.getRoute());
    }

    @Test
    void hardPhaseLimitRequestsNextPhase() {
        AgentPlannerService.StrategyAnalysis analysis = new AgentPlannerService.StrategyAnalysis();

        WorkflowNodeResult<Void> result = decider.afterTurn(
                analysis, AgentTurnBudget.forExecutingTurn(4, 8),
                "BUILD", 4, 2, "normal response");

        assertEquals(WorkflowRoute.NEXT_PHASE, result.getRoute());
        assertTrue((Boolean) result.getStateUpdates().get("hardPhaseAdvance"));
    }

    @Test
    void terminalResponseStopsCurrentAttempt() {
        AgentPlannerService.StrategyAnalysis analysis = new AgentPlannerService.StrategyAnalysis();

        WorkflowNodeResult<Void> result = decider.afterTurn(
                analysis, AgentTurnBudget.forExecutingTurn(2, 4),
                "BUILD", 1, 2, "Goodbye");

        assertEquals(WorkflowRoute.STOP, result.getRoute());
    }
}
