package com.promptfuzzer.dto;

import com.fasterxml.jackson.databind.JsonNode;
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
    private JsonNode supportedActions;
    private String resourceScope;
    private JsonNode resourceObjects;
    private JsonNode entrypoints;
    private JsonNode permissionBoundaries;
    private JsonNode controlBoundaries;
    private String verificationStatus;
}
