package com.promptfuzzer.agent.workflow;

/**
 * Semantic routes emitted by workflow steps.
 */
public enum WorkflowRoute {
    CONTINUE,
    REPLAN,
    NEXT_PHASE,
    RETRY,
    REVIEW,
    SUCCESS,
    STOP,
    ERROR
}
