package com.promptfuzzer.dto;

import lombok.Data;

@Data
public class UpdateReconApplicationSurfaceRequest {

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
    private String verificationStatus;
}
