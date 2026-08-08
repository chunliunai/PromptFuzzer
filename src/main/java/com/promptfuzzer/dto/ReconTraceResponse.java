package com.promptfuzzer.dto;

import com.promptfuzzer.entity.ReconTrace;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReconTraceResponse {

    private Long id;
    private Long reconTaskId;
    private String actionType;
    private Integer targetChatIndex;
    private Integer turnIndex;
    private String requestMessage;
    private String rawResponse;
    private String extractedText;
    private String observation;
    private String evidenceType;
    private String status;
    private String errorMessage;
    private Long durationMs;
    private LocalDateTime createdAt;

    public static ReconTraceResponse from(ReconTrace trace) {
        ReconTraceResponse response = new ReconTraceResponse();
        response.setId(trace.getId());
        response.setReconTaskId(trace.getReconTaskId());
        response.setActionType(trace.getActionType().name());
        response.setTargetChatIndex(trace.getTargetChatIndex());
        response.setTurnIndex(trace.getTurnIndex());
        response.setRequestMessage(trace.getRequestMessage());
        response.setRawResponse(trace.getRawResponse());
        response.setExtractedText(trace.getExtractedText());
        response.setObservation(trace.getObservation());
        response.setEvidenceType(trace.getEvidenceType() != null
                ? trace.getEvidenceType().name() : null);
        response.setStatus(trace.getStatus().name());
        response.setErrorMessage(trace.getErrorMessage());
        response.setDurationMs(trace.getDurationMs());
        response.setCreatedAt(trace.getCreatedAt());
        return response;
    }
}
