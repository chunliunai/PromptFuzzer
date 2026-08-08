package com.promptfuzzer.agent.workflow;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowNodeResultTest {

    @Test
    void continueResultUsesContinueRoute() {
        WorkflowNodeResult<String> result = WorkflowNodeResult.continueWith("plan");

        assertEquals("plan", result.getOutput());
        assertEquals(WorkflowRoute.CONTINUE, result.getRoute());
        assertEquals(Map.of(), result.getStateUpdates());
    }

    @Test
    void stateUpdatesAreExposedAsImmutableSnapshot() {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("strategyPlan", "v1");
        WorkflowNodeResult<String> result = WorkflowNodeResult.<String>builder()
                .output("v1")
                .route(WorkflowRoute.CONTINUE)
                .stateUpdates(updates)
                .build();

        updates.put("strategyPlan", "v2");

        assertEquals("v1", result.getStateUpdates().get("strategyPlan"));
        assertThrows(UnsupportedOperationException.class,
                () -> result.getStateUpdates().put("phase", "BUILD"));
    }
}
