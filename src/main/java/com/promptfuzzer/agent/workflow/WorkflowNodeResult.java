package com.promptfuzzer.agent.workflow;

import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Common result contract for workflow nodes.
 */
@Value
public class WorkflowNodeResult<T> {
    T output;
    WorkflowRoute route;
    String verdict;
    String evidence;
    Exception error;
    Map<String, Object> stateUpdates;

    @Builder(toBuilder = true)
    public WorkflowNodeResult(T output,
                              WorkflowRoute route,
                              String verdict,
                              String evidence,
                              Exception error,
                              Map<String, Object> stateUpdates) {
        this.output = output;
        this.route = route;
        this.verdict = verdict;
        this.evidence = evidence;
        this.error = error;
        this.stateUpdates = stateUpdates == null || stateUpdates.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(stateUpdates));
    }

    public static <T> WorkflowNodeResult<T> continueWith(T output) {
        return WorkflowNodeResult.<T>builder()
                .output(output)
                .route(WorkflowRoute.CONTINUE)
                .build();
    }

    public static <T> WorkflowNodeResult<T> error(Exception error, String evidence) {
        return WorkflowNodeResult.<T>builder()
                .route(WorkflowRoute.ERROR)
                .evidence(evidence)
                .error(error)
                .build();
    }

}
