package com.promptfuzzer.agent.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentWorkflowLoopControllerTest {

    private final AgentWorkflowLoopController controller = new AgentWorkflowLoopController();

    @Test
    void retryCountMeansAdditionalAttempts() {
        assertEquals(List.of(0, 1, 2), controller.retryIndexes(2));
    }

    @Test
    void turnIndexesAreOneBasedAndInclusive() {
        assertEquals(List.of(1, 2, 3, 4), controller.turnIndexes(4));
    }

    @Test
    void negativeBudgetsProduceEmptyOrInitialOnlySequences() {
        assertEquals(List.of(0), controller.retryIndexes(-1));
        assertEquals(List.of(), controller.turnIndexes(-1));
    }

    @Test
    void attemptRouteDistinguishesRetryAndReview() {
        assertEquals(WorkflowRoute.RETRY,
                controller.afterAttempt(0, 1,
                        AgentWorkflowLoopController.AgentSessionStatus.BLOCKED));
        assertEquals(WorkflowRoute.REVIEW,
                controller.afterAttempt(1, 1,
                        AgentWorkflowLoopController.AgentSessionStatus.BLOCKED));
        assertEquals(WorkflowRoute.SUCCESS,
                controller.afterAttempt(0, 1,
                        AgentWorkflowLoopController.AgentSessionStatus.SUCCESS));
    }
}
