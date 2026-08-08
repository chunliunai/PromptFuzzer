package com.promptfuzzer.agent.workflow;

public final class AgentWorkflowNodes {
    public static final String WORKFLOW_ENGINE = "WORKFLOW_ENGINE";
    public static final String INDEPENDENT_RECON = "INDEPENDENT_RECON";
    public static final String RETRY_PREPARATION = "RETRY_PREPARATION";
    public static final String STRATEGY_PLAN = "STRATEGY_PLAN";
    public static final String ANALYZE = "ANALYZE";
    public static final String GENERATE_PAYLOAD = "GENERATE_PAYLOAD";
    public static final String EXECUTE_TARGET = "EXECUTE_TARGET";
    public static final String STRATEGY_CHECK = "STRATEGY_CHECK";
    public static final String REPLAN = "REPLAN";
    public static final String COMPOSITE_REVIEW = "COMPOSITE_REVIEW";
    public static final String PHASE_TRANSITION = "PHASE_TRANSITION";
    public static final String JUDGE_OR_EXTRACT = "JUDGE_OR_EXTRACT";
    public static final String MEMORY_DELTA = "MEMORY_DELTA";
    public static final String FINAL_SYNC = "FINAL_SYNC";

    private AgentWorkflowNodes() {
    }
}
