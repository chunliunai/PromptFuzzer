package com.promptfuzzer.agent.workflow;

import com.promptfuzzer.service.AgentPlannerService;
import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class AgentWorkflowTurnOutput {
    AgentPlannerService.StrategyAnalysis analysis;
    Map<String, Object> turnRecord;
    String attackSignals;
    int targetChatIndex;
}
