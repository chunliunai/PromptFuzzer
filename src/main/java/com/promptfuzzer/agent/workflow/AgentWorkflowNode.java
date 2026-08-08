package com.promptfuzzer.agent.workflow;

/**
 * Minimal workflow node contract used by the legacy Agent executor.
 * Phase 2 keeps the legacy loop, while selected low-risk steps can be
 * delegated to nodes behind a feature flag.
 */
public interface AgentWorkflowNode {

    String name();

    default boolean supports(AgentWorkflowState state) {
        return true;
    }
}
