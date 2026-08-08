package com.promptfuzzer.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.entity.ReconAttackUnit;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ReconAttackUnitResponse {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private Long id;
    private Long reconResultId;
    private String generationId;
    private String unitKey;
    private String title;
    private String description;
    private List<Long> primarySurfaceIds;
    private List<Long> supportingSurfaceIds;
    private List<Long> negativeBoundarySurfaceIds;
    private String resourceBoundary;
    private List<String> securityControls;
    private List<String> riskDimensions;
    private String aggregationReason;
    private String riskLevel;
    private String confidence;
    private JsonNode sourceSurfaces;
    private Boolean createdByAi;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ReconAttackUnitResponse from(ReconAttackUnit unit) {
        ReconAttackUnitResponse response = new ReconAttackUnitResponse();
        response.setId(unit.getId());
        response.setReconResultId(unit.getReconResultId());
        response.setGenerationId(unit.getGenerationId());
        response.setUnitKey(unit.getUnitKey());
        response.setTitle(unit.getTitle());
        response.setDescription(unit.getDescription());
        response.setPrimarySurfaceIds(parseLongList(unit.getPrimarySurfaceIds()));
        response.setSupportingSurfaceIds(
                parseLongList(unit.getSupportingSurfaceIds()));
        response.setNegativeBoundarySurfaceIds(
                parseLongList(unit.getNegativeBoundarySurfaceIds()));
        response.setResourceBoundary(unit.getResourceBoundary());
        response.setSecurityControls(parseStringList(unit.getSecurityControls()));
        response.setRiskDimensions(parseStringList(unit.getRiskDimensions()));
        response.setAggregationReason(unit.getAggregationReason());
        response.setRiskLevel(unit.getRiskLevel() != null
                ? unit.getRiskLevel().name() : null);
        response.setConfidence(unit.getConfidence() != null
                ? unit.getConfidence().name() : null);
        response.setSourceSurfaces(parseJson(unit.getSourceSurfaceSnapshot()));
        response.setCreatedByAi(unit.getCreatedByAi());
        response.setCreatedAt(unit.getCreatedAt());
        response.setUpdatedAt(unit.getUpdatedAt());
        return response;
    }

    private static List<Long> parseLongList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<List<Long>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static List<String> parseStringList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    value, new TypeReference<List<String>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static JsonNode parseJson(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ignored) {
            return objectMapper.createArrayNode();
        }
    }
}
