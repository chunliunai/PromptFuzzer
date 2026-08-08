package com.promptfuzzer.dto;

import lombok.Data;

import java.util.List;

@Data
public class CreateReconAttackCandidateRequest {

    private List<Long> surfaceIds;
    private List<Long> attackUnitIds;
    private Integer maxAttackUnits = 8;
    private Integer candidateCountPerAttackUnit;
    private Integer candidateCountPerSurface = 3;
    private List<String> goalPreferences;

    public Integer resolveCandidateCountPerAttackUnit() {
        return candidateCountPerAttackUnit != null
                ? candidateCountPerAttackUnit : candidateCountPerSurface;
    }
}
