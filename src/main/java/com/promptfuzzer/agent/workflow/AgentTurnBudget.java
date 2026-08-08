package com.promptfuzzer.agent.workflow;

/**
 * Explicit turn-budget semantics for one attempt.
 *
 * currentTurn is one-based while a turn is executing. A value of zero means
 * planning before the first turn.
 */
public record AgentTurnBudget(
        int maxTurns,
        int currentTurn,
        int remainingTurnsIncludingCurrent,
        int remainingTurnsAfterCurrent) {

    public static AgentTurnBudget beforeFirstTurn(int maxTurns) {
        int normalizedMax = Math.max(0, maxTurns);
        return new AgentTurnBudget(normalizedMax, 0, normalizedMax, normalizedMax);
    }

    public static AgentTurnBudget forExecutingTurn(int currentTurn, int maxTurns) {
        if (currentTurn < 1) {
            throw new IllegalArgumentException("currentTurn must be one-based while executing");
        }
        int normalizedMax = Math.max(0, maxTurns);
        int includingCurrent = Math.max(0, normalizedMax - currentTurn + 1);
        int afterCurrent = Math.max(0, normalizedMax - currentTurn);
        return new AgentTurnBudget(normalizedMax, currentTurn, includingCurrent, afterCurrent);
    }

    public boolean hasCurrentTurn() {
        return remainingTurnsIncludingCurrent > 0;
    }

    public boolean hasNextTurn() {
        return remainingTurnsAfterCurrent > 0;
    }
}
