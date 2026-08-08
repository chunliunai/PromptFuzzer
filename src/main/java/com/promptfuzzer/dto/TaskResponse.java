package com.promptfuzzer.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.entity.Task;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class TaskResponse {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private Long id;
    private String name;
    private String scanMode;
    private String status;
    private int totalCount;
    private int successCount;
    private int failCount;
    private int uncertainCount;
    private String reportSummary;
    private String attackMode;
    private Integer maxTurns;
    private Long reconResultId;
    private Boolean supplementalRecon;
    private List<Long> selectedSurfaceIds;
    private Integer agentCount;
    private Integer agentSuccessCount;
    private Integer agentBlockedCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static TaskResponse from(Task task) {
        TaskResponse r = new TaskResponse();
        r.setId(task.getId());
        r.setName(task.getName());
        r.setScanMode(task.getScanMode().name());
        r.setStatus(task.getStatus().name());
        r.setTotalCount(task.getTotalCount());
        r.setSuccessCount(task.getSuccessCount());
        r.setFailCount(task.getFailCount());
        r.setUncertainCount(task.getUncertainCount());
        r.setReportSummary(task.getReportSummary());
        r.setAttackMode(task.getAttackMode() != null ? task.getAttackMode().name() : "SINGLE_TURN");
        r.setMaxTurns(task.getMaxTurns());
        r.setReconResultId(task.getReconResultId());
        r.setSupplementalRecon(task.getSupplementalRecon());
        r.setSelectedSurfaceIds(parseSelectedSurfaceIds(task.getSelectedSurfaceIds()));
        r.setCreatedAt(task.getCreatedAt());
        r.setUpdatedAt(task.getUpdatedAt());
        return r;
    }

    public static TaskResponse from(Task task, int agentCount) {
        TaskResponse r = from(task);
        r.setAgentCount(agentCount);
        return r;
    }

    private static List<Long> parseSelectedSurfaceIds(String selectedSurfaceIds) {
        if (selectedSurfaceIds == null || selectedSurfaceIds.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    selectedSurfaceIds, new TypeReference<List<Long>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
