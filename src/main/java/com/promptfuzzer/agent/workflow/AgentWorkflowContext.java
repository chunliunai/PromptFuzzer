package com.promptfuzzer.agent.workflow;

import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.Task;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Runtime objects that are useful to nodes but should not be persisted in state.
 */
@Data
@Builder
public class AgentWorkflowContext {
    private Task task;
    private AgentSession session;
    private List<String> availableTechniques;
    private boolean browserMode;
}
