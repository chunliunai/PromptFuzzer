package com.promptfuzzer.dto;

import com.promptfuzzer.entity.ReconApplicationSurface;
import com.promptfuzzer.service.ReconSurfaceDataCodec;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ReconApplicationSurfaceResponse {

    private Long id;
    private Long reconResultId;
    private Long parentSurfaceId;
    private String capabilityKey;
    private String surfaceLevel;
    private Boolean selectable;
    private String title;
    private String displayDescription;
    private String description;
    private String surfaceType;
    private String relatedTool;
    private List<String> supportedActions;
    private String resourceScope;
    private List<String> resourceObjects;
    private List<String> entrypoints;
    private List<String> permissionBoundaries;
    private List<String> controlBoundaries;
    private String evidenceSource;
    private String verificationStatus;
    private List<Long> sourceTraceIds;
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
        response.setDisplayDescription(surface.getCurrentDescription());
        response.setDescription(surface.getCurrentDescription());
        response.setSurfaceType(surface.getSurfaceType());
        response.setRelatedTool(surface.getRelatedTool());
        response.setSupportedActions(ReconSurfaceDataCodec.readStringArray(
                surface.getSupportedActions()));
        response.setResourceScope(surface.getResourceScope());
        response.setResourceObjects(ReconSurfaceDataCodec.readStringArray(
                surface.getResourceObjects()));
        response.setEntrypoints(ReconSurfaceDataCodec.readStringArray(
                surface.getEntrypoints()));
        response.setPermissionBoundaries(ReconSurfaceDataCodec.readStringArray(
                surface.getPermissionBoundaries()));
        response.setControlBoundaries(ReconSurfaceDataCodec.readStringArray(
                surface.getControlBoundaries()));
        response.setEvidenceSource(surface.getEvidenceSource() != null
                ? surface.getEvidenceSource().name() : null);
        response.setVerificationStatus(surface.getVerificationStatus() != null
                ? surface.getVerificationStatus().name() : null);
        response.setSourceTraceIds(ReconSurfaceDataCodec.readLongArray(
                surface.getSourceTraceIds()));
        response.setSourceType(surface.getSourceType() != null
                ? surface.getSourceType().name() : null);
        response.setUserEdited(surface.getUserEdited());
        response.setCreatedAt(surface.getCreatedAt());
        response.setUpdatedAt(surface.getUpdatedAt());
        return response;
    }
}
