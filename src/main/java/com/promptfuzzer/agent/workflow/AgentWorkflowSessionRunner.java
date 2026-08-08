package com.promptfuzzer.agent.workflow;

/**
 * Executes the session lifecycle owned by the Workflow layer.
 */
public interface AgentWorkflowSessionRunner {

    void runSession(Long sessionId, Long existingBrowserSessionId);
}
