package com.promptfuzzer.agent.workflow;

import lombok.Builder;
import lombok.Data;

/**
 * Shared state snapshot passed to workflow nodes.
 */
@Data
@Builder(toBuilder = true)
public class AgentWorkflowState {
    private Long taskId;
    private Long agentSessionId;
    private Integer sessionIndex;

    private String goal;
    private String phase;
    private Integer turnIndex;
    private Integer retryIndex;
    private Integer targetChatIndex;
    private Integer maxTurns;
    private Integer remainingTurnsIncludingCurrent;
    private Integer remainingTurnsAfterCurrent;
    private Integer turnsInPhase;
    private Integer retryCount;
    private WorkflowRoute route;

    private String attackContext;
    private String targetIntelligenceMemory;
    private String buildMemory;
    private String attackSignals;
    private String strategyPlan;

    private String message;
    private String rawResponse;
    private String extractedText;
    private String verdict;
    private String evidence;
}
