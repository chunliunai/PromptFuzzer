package com.promptfuzzer.dto;

import com.promptfuzzer.entity.ReconResult;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReconResultResponse {

    private Long id;
    private Long reconTaskId;
    private String targetFingerprint;
    private String businessProfile;
    private String responseProfile;
    private String capabilityInventory;
    private String openDiscovery;
    private String coveragePlan;
    private String coverageMatrix;
    private String capabilityFacts;
    private String toolInventory;
    private String unresolvedCapabilities;
    private String supportingEvidence;
    private String unresolvedQuestions;
    private String surfaceQualityIssues;
    private String targetIntelligenceMemory;
    private String qualityStatus;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ReconResultResponse from(ReconResult result) {
        ReconResultResponse response = new ReconResultResponse();
        response.setId(result.getId());
        response.setReconTaskId(result.getReconTaskId());
        response.setTargetFingerprint(result.getTargetFingerprint());
        response.setBusinessProfile(result.getBusinessProfile());
        response.setResponseProfile(result.getResponseProfile());
        response.setCapabilityInventory(result.getCapabilityInventory());
        response.setOpenDiscovery(result.getOpenDiscovery());
        response.setCoveragePlan(result.getCoveragePlan());
        response.setCoverageMatrix(result.getCoverageMatrix());
        response.setCapabilityFacts(result.getCapabilityFacts());
        response.setToolInventory(result.getToolInventory());
        response.setUnresolvedCapabilities(result.getUnresolvedCapabilities());
        response.setSupportingEvidence(result.getSupportingEvidence());
        response.setUnresolvedQuestions(result.getUnresolvedQuestions());
        response.setSurfaceQualityIssues(result.getSurfaceQualityIssues());
        response.setTargetIntelligenceMemory(result.getTargetIntelligenceMemory());
        response.setQualityStatus(result.getQualityStatus().name());
        response.setVersion(result.getVersion());
        response.setCreatedAt(result.getCreatedAt());
        response.setUpdatedAt(result.getUpdatedAt());
        return response;
    }
}
