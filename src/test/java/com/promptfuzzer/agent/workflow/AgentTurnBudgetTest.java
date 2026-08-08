package com.promptfuzzer.agent.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTurnBudgetTest {

    @Test
    void beforeFirstTurnExposesFullBudget() {
        AgentTurnBudget budget = AgentTurnBudget.beforeFirstTurn(6);

        assertEquals(0, budget.currentTurn());
        assertEquals(6, budget.remainingTurnsIncludingCurrent());
        assertEquals(6, budget.remainingTurnsAfterCurrent());
        assertTrue(budget.hasCurrentTurn());
        assertTrue(budget.hasNextTurn());
    }

    @Test
    void executingTurnSeparatesCurrentAndFutureBudget() {
        AgentTurnBudget budget = AgentTurnBudget.forExecutingTurn(3, 6);

        assertEquals(4, budget.remainingTurnsIncludingCurrent());
        assertEquals(3, budget.remainingTurnsAfterCurrent());
        assertTrue(budget.hasCurrentTurn());
        assertTrue(budget.hasNextTurn());
    }

    @Test
    void finalTurnCanExecuteButCannotReplanForAnotherTurn() {
        AgentTurnBudget budget = AgentTurnBudget.forExecutingTurn(6, 6);

        assertEquals(1, budget.remainingTurnsIncludingCurrent());
        assertEquals(0, budget.remainingTurnsAfterCurrent());
        assertTrue(budget.hasCurrentTurn());
        assertFalse(budget.hasNextTurn());
    }

    @Test
    void rejectsZeroBasedExecutingTurn() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentTurnBudget.forExecutingTurn(0, 6));
    }
}
