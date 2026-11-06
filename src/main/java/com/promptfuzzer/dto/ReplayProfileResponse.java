package com.promptfuzzer.dto;

import com.promptfuzzer.entity.ReplayProfile;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReplayProfileResponse {
    private Long id;
    private Long taskId;
    private Long agentSessionId;
    private Integer sessionIndex;
    private Integer turnIndex;
    private Integer targetChatIndex;
    private String targetType;
    private String endpointFingerprint;
    private String classification;
    private String method;
    private String url;
    private String requestTemplate;
    private String payloadFieldPath;
    private String dynamicBindings;
    private String responseExtractionRule;
    private Integer validationCount;
    private LocalDateTime lastValidatedAt;
    private Integer failureCount;
    private String lastFailureReason;
    private String evidenceSummary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ReplayProfileResponse from(ReplayProfile profile) {
        ReplayProfileResponse response = new ReplayProfileResponse();
        response.setId(profile.getId());
        response.setTaskId(profile.getTaskId());
        response.setAgentSessionId(profile.getAgentSessionId());
        response.setSessionIndex(profile.getSessionIndex());
        response.setTurnIndex(profile.getTurnIndex());
        response.setTargetChatIndex(profile.getTargetChatIndex());
        response.setTargetType(profile.getTargetType());
        response.setEndpointFingerprint(profile.getEndpointFingerprint());
        response.setClassification(profile.getClassification().name());
        response.setMethod(profile.getMethod());
        response.setUrl(profile.getUrl());
        response.setRequestTemplate(profile.getRequestTemplate());
        response.setPayloadFieldPath(profile.getPayloadFieldPath());
        response.setDynamicBindings(profile.getDynamicBindings());
        response.setResponseExtractionRule(profile.getResponseExtractionRule());
        response.setValidationCount(profile.getValidationCount());
        response.setLastValidatedAt(profile.getLastValidatedAt());
        response.setFailureCount(profile.getFailureCount());
        response.setLastFailureReason(profile.getLastFailureReason());
        response.setEvidenceSummary(profile.getEvidenceSummary());
        response.setCreatedAt(profile.getCreatedAt());
        response.setUpdatedAt(profile.getUpdatedAt());
        return response;
    }
}
