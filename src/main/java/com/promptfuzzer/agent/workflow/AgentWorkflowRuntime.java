package com.promptfuzzer.agent.workflow;

import com.promptfuzzer.agent.workflow.node.CompositeReviewNode;
import com.promptfuzzer.agent.workflow.node.FinalSyncNode;
import com.promptfuzzer.agent.workflow.node.IndependentReconNode;
import com.promptfuzzer.agent.workflow.node.PhaseTransitionNode;
import com.promptfuzzer.agent.workflow.node.ReplanNode;
import com.promptfuzzer.agent.workflow.node.RetryPreparationNode;
import com.promptfuzzer.agent.workflow.node.StrategyCheckNode;
import com.promptfuzzer.dto.ReconConfig;
import com.promptfuzzer.service.AgentPlannerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Node and routing operations used by both the top-level Engine and its
 * session runner. Keeping these operations here prevents a circular
 * Engine -> Runner -> Engine dependency.
 */
@Component
@RequiredArgsConstructor
public class AgentWorkflowRuntime {

    private final List<AgentWorkflowNode> nodes;
    private final AgentWorkflowTurnCoordinator turnCoordinator;
    private final AgentWorkflowRouteDecider routeDecider;
    private final AgentWorkflowLoopController loopController;
    private final IndependentReconNode independentReconNode;
    private final RetryPreparationNode retryPreparationNode;
    private final StrategyCheckNode strategyCheckNode;
    private final ReplanNode replanNode;
    private final PhaseTransitionNode phaseTransitionNode;
    private final CompositeReviewNode compositeReviewNode;
    private final FinalSyncNode finalSyncNode;

    public List<String> nodeNames() {
        return nodes.stream().map(AgentWorkflowNode::name).toList();
    }

    public List<Integer> retryIndexes(int retryCount) {
        return loopController.retryIndexes(retryCount);
    }

    public List<Integer> turnIndexes(int maxTurns) {
        return loopController.turnIndexes(maxTurns);
    }

    public WorkflowNodeResult<AgentWorkflowTurnOutput> executeTurn(AgentWorkflowTurnRequest request) {
        return turnCoordinator.execute(request);
    }

    public WorkflowNodeResult<Void> routeAfterStrategyCheck(
            AgentPlannerService.StrategyCheckResult check) {
        return routeDecider.afterStrategyCheck(check);
    }

    public WorkflowNodeResult<Void> routeAfterTurn(
            AgentPlannerService.StrategyAnalysis analysis,
            AgentTurnBudget turnBudget,
            String phase,
            int turnsInPhase,
            int phaseMaxTurns,
            String extractedText) {
        return routeDecider.afterTurn(
                analysis, turnBudget, phase, turnsInPhase, phaseMaxTurns, extractedText);
    }

    public WorkflowNodeResult<RetryPreparationNode.RetryPreparationOutput> prepareRetry(
            AgentWorkflowContext context,
            AgentWorkflowState state,
            Long browserSessionId,
            List<String> availableTechniques,
            String intelligenceLog,
            String recentPayloads,
            String replanReason) throws Exception {
        return retryPreparationNode.execute(context, state, browserSessionId, availableTechniques,
                intelligenceLog, recentPayloads, replanReason);
    }

    public WorkflowNodeResult<IndependentReconNode.IndependentReconOutput> executeIndependentRecon(
            AgentWorkflowContext context,
            AgentWorkflowState state,
            Long browserSessionId,
            ReconConfig reconConfig) {
        return independentReconNode.execute(context, state, browserSessionId, reconConfig);
    }

    public AgentPlannerService.StrategyCheckResult checkStrategy(
            AgentWorkflowContext context,
            AgentWorkflowState state,
            String payload1,
            String payload2,
            String payload3,
            String response1,
            String response2,
            String response3,
            String weaponHistory) throws Exception {
        return strategyCheckNode.execute(context, state, payload1, payload2, payload3,
                response1, response2, response3, weaponHistory);
    }

    public String replan(AgentWorkflowContext context,
                         AgentWorkflowState state,
                         String trigger,
                         List<String> availableTechniques,
                         int remainingTurns,
                         String intelligenceLog,
                         String previousStrategyPlan,
                         String recentPayloads,
                         String replanReason) throws Exception {
        return replanNode.execute(context, state, trigger, availableTechniques, remainingTurns,
                intelligenceLog, previousStrategyPlan, recentPayloads, replanReason);
    }

    public String transitionPhase(AgentWorkflowContext context,
                                  AgentWorkflowState state,
                                  int turnsInPhase,
                                  String observations,
                                  String targetMemory,
                                  String buildMemory,
                                  String strategyPlan) {
        return phaseTransitionNode.execute(context, state, turnsInPhase, observations,
                targetMemory, buildMemory, strategyPlan);
    }

    public CompositeReviewNode.ReviewResult compositeReview(
            AgentWorkflowContext context,
            AgentWorkflowState state,
            List<Map<String, Object>> turnRecords,
            String scope) {
        return compositeReviewNode.execute(context, state, turnRecords, scope);
    }

    public void finalSync(AgentWorkflowContext context,
                          AgentWorkflowState state,
                          List<Map<String, Object>> allTurnRecords) {
        finalSyncNode.execute(context, state, allTurnRecords);
    }
}
