package com.promptfuzzer.dto;

import lombok.Data;

/**
 * Optional configuration for the independent RECON subroutine.
 * The public API stays the same; this object only tunes the internal pre-attack
 * intelligence collection budget.
 */
@Data
public class ReconConfig {

    private Boolean enabled = true;

    /**
     * AUTO / LIGHT / STANDARD / DEEP / SKIP_TARGET_PROBE.
     */
    private String mode = "AUTO";

    private Integer maxChats;

    private Integer turnsPerChat;

    private Integer maxTotalTurns;

    private Boolean cleanBeforeAttack = true;
}
