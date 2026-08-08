package com.promptfuzzer.dto;

import lombok.Data;

import java.util.List;

@Data
public class CreateReconAttackUnitRequest {

    private List<Long> surfaceIds;
    private Integer maxAttackUnits = 8;
}
