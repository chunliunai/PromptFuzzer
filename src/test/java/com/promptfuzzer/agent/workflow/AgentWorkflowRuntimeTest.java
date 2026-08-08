package com.promptfuzzer.agent.workflow;

import com.promptfuzzer.agent.workflow.node.CompositeReviewNode;
import com.promptfuzzer.agent.workflow.node.FinalSyncNode;
import com.promptfuzzer.agent.workflow.node.IndependentReconNode;
import com.promptfuzzer.agent.workflow.node.PhaseTransitionNode;
import com.promptfuzzer.agent.workflow.node.ReplanNode;
import com.promptfuzzer.agent.workflow.node.RetryPreparationNode;
import com.promptfuzzer.agent.workflow.node.StrategyCheckNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWorkflowRuntimeTest {

    @Test
    void exposesRegisteredNodeNames() {
        AgentWorkflowNode first = mock(AgentWorkflowNode.class);
        AgentWorkflowNode second = mock(AgentWorkflowNode.class);
        when(first.name()).thenReturn("FIRST");
        when(second.name()).thenReturn("SECOND");

        AgentWorkflowRuntime runtime = runtime(
                List.of(first, second), mock(AgentWorkflowLoopController.class));

        assertEquals(List.of("FIRST", "SECOND"), runtime.nodeNames());
    }

    @Test
    void delegatesLoopIndexesToController() {
        AgentWorkflowLoopController loopController = mock(AgentWorkflowLoopController.class);
        when(loopController.retryIndexes(2)).thenReturn(List.of(0, 1, 2));
        when(loopController.turnIndexes(3)).thenReturn(List.of(1, 2, 3));
        AgentWorkflowRuntime runtime = runtime(List.of(), loopController);

        assertEquals(List.of(0, 1, 2), runtime.retryIndexes(2));
        assertEquals(List.of(1, 2, 3), runtime.turnIndexes(3));
        verify(loopController).retryIndexes(2);
        verify(loopController).turnIndexes(3);
    }

    private AgentWorkflowRuntime runtime(List<AgentWorkflowNode> nodes,
                                         AgentWorkflowLoopController loopController) {
        return new AgentWorkflowRuntime(
                nodes,
                mock(AgentWorkflowTurnCoordinator.class),
                mock(AgentWorkflowRouteDecider.class),
                loopController,
                mock(IndependentReconNode.class),
                mock(RetryPreparationNode.class),
                mock(StrategyCheckNode.class),
                mock(ReplanNode.class),
                mock(PhaseTransitionNode.class),
                mock(CompositeReviewNode.class),
                mock(FinalSyncNode.class));
    }
}
