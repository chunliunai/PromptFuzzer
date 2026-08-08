package com.promptfuzzer.service;

import com.promptfuzzer.entity.ReconTrace;
import com.promptfuzzer.repository.ReconTraceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReconTraceRecorder {

    private static final int ERROR_MAX_CHARS = 2000;

    private final ReconTraceRepository traceRepository;

    public ReconTrace success(Long reconTaskId,
                              ReconTrace.ActionType actionType,
                              Integer targetChatIndex,
                              Integer turnIndex,
                              String requestMessage,
                              String rawResponse,
                              String extractedText,
                              String observation,
                              ReconTrace.EvidenceType evidenceType,
                              long startedAtMs) {
        return record(reconTaskId, actionType, targetChatIndex, turnIndex, requestMessage,
                rawResponse, extractedText, observation, evidenceType,
                ReconTrace.Status.SUCCESS, null, startedAtMs);
    }

    public ReconTrace error(Long reconTaskId,
                            ReconTrace.ActionType actionType,
                            Integer targetChatIndex,
                            Integer turnIndex,
                            String requestMessage,
                            Exception error,
                            long startedAtMs) {
        return record(reconTaskId, actionType, targetChatIndex, turnIndex, requestMessage,
                null, null, null, null, ReconTrace.Status.ERROR,
                error != null ? error.getMessage() : "Unknown error", startedAtMs);
    }

    private ReconTrace record(Long reconTaskId,
                              ReconTrace.ActionType actionType,
                              Integer targetChatIndex,
                              Integer turnIndex,
                              String requestMessage,
                              String rawResponse,
                              String extractedText,
                              String observation,
                              ReconTrace.EvidenceType evidenceType,
                              ReconTrace.Status status,
                              String errorMessage,
                              long startedAtMs) {
        ReconTrace trace = new ReconTrace();
        trace.setReconTaskId(reconTaskId);
        trace.setActionType(actionType);
        trace.setTargetChatIndex(targetChatIndex);
        trace.setTurnIndex(turnIndex);
        trace.setRequestMessage(requestMessage);
        trace.setRawResponse(rawResponse);
        trace.setExtractedText(extractedText);
        trace.setObservation(observation);
        trace.setEvidenceType(evidenceType);
        trace.setStatus(status);
        trace.setErrorMessage(truncate(errorMessage, ERROR_MAX_CHARS));
        trace.setDurationMs(Math.max(0, System.currentTimeMillis() - startedAtMs));
        return traceRepository.save(trace);
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...";
    }
}
