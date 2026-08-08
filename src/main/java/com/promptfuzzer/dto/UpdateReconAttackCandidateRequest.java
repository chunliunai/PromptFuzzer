package com.promptfuzzer.dto;

import lombok.Data;

@Data
public class UpdateReconAttackCandidateRequest {

    private String title;
    private String description;
    private String recommendedGoal;
    private String recommendedAttackMode;
    private String attackContext;
    private String requestBody;
    private String status;
}
