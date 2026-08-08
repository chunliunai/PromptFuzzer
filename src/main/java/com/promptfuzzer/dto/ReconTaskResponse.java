package com.promptfuzzer.dto;

import com.promptfuzzer.entity.ReconTask;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReconTaskResponse {

    private Long id;
    private String name;
    private String scanMode;
    private String reconContext;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    public static ReconTaskResponse from(ReconTask task) {
        ReconTaskResponse response = new ReconTaskResponse();
        response.setId(task.getId());
        response.setName(task.getName());
        response.setScanMode(task.getScanMode().name());
        response.setReconContext(task.getReconContext());
        response.setStatus(task.getStatus().name());
        response.setErrorMessage(task.getErrorMessage());
        response.setCreatedAt(task.getCreatedAt());
        response.setUpdatedAt(task.getUpdatedAt());
        response.setCompletedAt(task.getCompletedAt());
        return response;
    }
}
