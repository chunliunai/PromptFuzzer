package com.promptfuzzer.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.entity.ReconAttackCandidate;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ReconAttackCandidateResponse {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private Long id;
    private Long reconResultId;
    private Long attackUnitId;
    private List<Long> surfaceIds;
    private String title;
    private String description;
    private String recommendedGoal;
    private String recommendedAttackMode;
    private String rationale;
    private String attackContext;
    private JsonNode requestBody;
    private String status;
    private Long taskId;
    private Boolean createdByAi;
    private Boolean userEdited;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ReconAttackCandidateResponse from(ReconAttackCandidate candidate) {
        ReconAttackCandidateResponse response = new ReconAttackCandidateResponse();
        response.setId(candidate.getId());
        response.setReconResultId(candidate.getReconResultId());
        response.setAttackUnitId(candidate.getAttackUnitId());
        response.setSurfaceIds(parseSurfaceIds(candidate.getSurfaceIds()));
        response.setTitle(candidate.getTitle());
        response.setDescription(candidate.getDescription());
        response.setRecommendedGoal(candidate.getRecommendedGoal());
        response.setRecommendedAttackMode(candidate.getRecommendedAttackMode());
        response.setRationale(candidate.getRationale());
        response.setAttackContext(candidate.getAttackContext());
        response.setRequestBody(parseJson(candidate.getRequestBody()));
        response.setStatus(candidate.getStatus() != null
                ? candidate.getStatus().name() : null);
        response.setTaskId(candidate.getTaskId());
        response.setCreatedByAi(candidate.getCreatedByAi());
        response.setUserEdited(candidate.getUserEdited());
        response.setCreatedAt(candidate.getCreatedAt());
        response.setUpdatedAt(candidate.getUpdatedAt());
        return response;
    }

    private static List<Long> parseSurfaceIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<List<Long>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static JsonNode parseJson(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }
}
