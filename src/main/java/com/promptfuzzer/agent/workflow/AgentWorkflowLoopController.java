package com.promptfuzzer.agent.workflow;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

@Component
public class AgentWorkflowLoopController {

    public List<Integer> retryIndexes(int retryCount) {
        int normalizedRetryCount = Math.max(0, retryCount);
        return IntStream.rangeClosed(0, normalizedRetryCount)
                .boxed()
                .toList();
    }

    public List<Integer> turnIndexes(int maxTurns) {
        int normalizedMaxTurns = Math.max(0, maxTurns);
        return IntStream.rangeClosed(1, normalizedMaxTurns)
                .boxed()
                .toList();
    }

    public WorkflowRoute afterAttempt(int retryIndex,
                                      int retryCount,
                                      AgentSessionStatus status) {
        if (status == AgentSessionStatus.SUCCESS) {
            return WorkflowRoute.SUCCESS;
        }
        return retryIndex < Math.max(0, retryCount)
                ? WorkflowRoute.RETRY
                : WorkflowRoute.REVIEW;
    }

    public enum AgentSessionStatus {
        SUCCESS,
        BLOCKED,
        ERROR
    }
}
