package com.promptfuzzer.dto;

import lombok.Data;

@Data
public class ReplayProfileValidationRequest {
    private Long browserSessionId;
    private String prompt;
}
