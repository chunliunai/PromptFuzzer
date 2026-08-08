package com.promptfuzzer.dto;

import com.promptfuzzer.entity.ReconApplicationSurface;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReconApplicationSurfaceResponse {

    private Long id;
    private Long reconResultId;
    private Long parentSurfaceId;
    private String capabilityKey;
    private String surfaceLevel;
    private Boolean selectable;
    private String title;
    private String description;
    private String surfaceType;
    private String relatedTool;
    private String supportedActions;
    private String resourceScope;
    private String evidenceSource;
    private String verificationStatus;
    private String sourceTraceIds;
    private String sourceType;
    private Boolean userEdited;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ReconApplicationSurfaceResponse from(ReconApplicationSurface surface) {
        ReconApplicationSurfaceResponse response = new ReconApplicationSurfaceResponse();
        response.setId(surface.getId());
        response.setReconResultId(surface.getReconResultId());
        response.setParentSurfaceId(surface.getParentSurfaceId());
        response.setCapabilityKey(surface.getCapabilityKey());
        response.setSurfaceLevel(surface.getSurfaceLevel() != null
                ? surface.getSurfaceLevel().name() : "CAPABILITY");
        response.setSelectable(surface.getSelectable() == null
                || Boolean.TRUE.equals(surface.getSelectable()));
        response.setTitle(surface.getCurrentTitle());
        response.setDescription(surface.getCurrentDescription());
        response.setSurfaceType(surface.getSurfaceType());
        response.setRelatedTool(surface.getRelatedTool());
        response.setSupportedActions(surface.getSupportedActions());
        response.setResourceScope(surface.getResourceScope());
        response.setEvidenceSource(surface.getEvidenceSource() != null
                ? surface.getEvidenceSource().name() : null);
        response.setVerificationStatus(surface.getVerificationStatus() != null
                ? surface.getVerificationStatus().name() : null);
        response.setSourceTraceIds(surface.getSourceTraceIds());
        response.setSourceType(surface.getSourceType().name());
        response.setUserEdited(surface.getUserEdited());
        response.setCreatedAt(surface.getCreatedAt());
        response.setUpdatedAt(surface.getUpdatedAt());
        return response;
    }
}
