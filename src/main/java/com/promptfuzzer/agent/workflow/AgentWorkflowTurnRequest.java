package com.promptfuzzer.agent.workflow;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class AgentWorkflowTurnRequest {
    AgentWorkflowContext context;
    AgentWorkflowState state;
    String conversationJson;
    String intelligenceLog;
    List<Map<String, Object>> turnRecords;
    Long browserSessionId;
    String rawRequestTemplate;
}
