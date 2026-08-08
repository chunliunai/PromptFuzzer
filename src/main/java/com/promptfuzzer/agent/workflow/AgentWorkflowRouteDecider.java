package com.promptfuzzer.agent.workflow;

import com.promptfuzzer.service.AgentPlannerService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class AgentWorkflowRouteDecider {

    public WorkflowNodeResult<Void> afterStrategyCheck(AgentPlannerService.StrategyCheckResult check) {
        if (check == null) {
            return route(WorkflowRoute.CONTINUE, null, Map.of());
        }
        if ("DEAD".equals(check.getStatus())) {
            return route(WorkflowRoute.STOP, check.getReason(), Map.of("strategyDead", true));
        }
        String status = value(check.getStatus());
        String routeHealth = value(check.getRouteHealth());
        boolean shouldReplan = "STALE".equals(status)
                || "FAMILY_STALE".equals(status)
                || (check.isShouldReplan()
                && ("ROUTE_INVALIDATED".equals(routeHealth)
                || "FAMILY_INVALIDATED".equals(routeHealth)
                || "DEAD".equals(routeHealth)
                || check.isShouldChangeFamily()));
        return route(shouldReplan ? WorkflowRoute.REPLAN : WorkflowRoute.CONTINUE,
                check.getReason(), Map.of("shouldChangeFamily", check.isShouldChangeFamily()));
    }

    public WorkflowNodeResult<Void> afterTurn(AgentPlannerService.StrategyAnalysis analysis,
                                              AgentTurnBudget turnBudget,
                                              String phase,
                                              int turnsInPhase,
                                              int phaseMaxTurns,
                                              String extractedText) {
        boolean terminalResponse = !"RECON".equals(phase) && isTerminalResponse(extractedText);
        boolean hardPhaseAdvance = analysis != null
                && !analysis.isPhaseTransition()
                && turnsInPhase >= Math.max(phaseMaxTurns, 4);
        boolean phaseTransition = analysis != null
                && (analysis.isPhaseTransition() || hardPhaseAdvance);
        boolean strategyInvalidated = analysis != null && analysis.isStrategyInvalidated();

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("terminalResponse", terminalResponse);
        updates.put("hardPhaseAdvance", hardPhaseAdvance);
        updates.put("phaseTransitionRequested", phaseTransition);
        updates.put("strategyInvalidated", strategyInvalidated);
        updates.put("hasNextTurn", turnBudget.hasNextTurn());

        if (terminalResponse) {
            return route(WorkflowRoute.STOP,
                    "目标会话已返回终止性回复或空响应", updates);
        }
        if (strategyInvalidated && turnBudget.hasNextTurn()) {
            return route(WorkflowRoute.REPLAN,
                    "Planner invalidated the active strategy", updates);
        }
        if (phaseTransition) {
            return route(WorkflowRoute.NEXT_PHASE, null, updates);
        }
        return route(WorkflowRoute.CONTINUE, null, updates);
    }

    private WorkflowNodeResult<Void> route(WorkflowRoute route,
                                           String evidence,
                                           Map<String, Object> stateUpdates) {
        return WorkflowNodeResult.<Void>builder()
                .route(route)
                .evidence(evidence)
                .stateUpdates(stateUpdates)
                .build();
    }

    private boolean isTerminalResponse(String response) {
        if (response == null) {
            return false;
        }
        String lower = response.toLowerCase(Locale.ROOT);
        return lower.contains("goodbye") || lower.contains("happy cooking") || lower.isBlank();
    }

    private String value(String value) {
        return value != null ? value : "";
    }
}
