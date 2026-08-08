package com.promptfuzzer.dto;

import com.promptfuzzer.entity.AgentWorkflowTrace;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentWorkflowTraceResponse {

    private Long id;
    private Long taskId;
    private Long agentSessionId;
    private Integer sessionIndex;
    private String nodeName;
    private String status;
    private String phase;
    private Integer retryIndex;
    private Integer turnIndex;
    private Integer targetChatIndex;
    private String inputSnapshot;
    private String outputSnapshot;
    private String verdict;
    private String evidence;
    private String errorMessage;
    private Long durationMs;
    private LocalDateTime createdAt;

    public static AgentWorkflowTraceResponse from(AgentWorkflowTrace trace) {
        AgentWorkflowTraceResponse r = new AgentWorkflowTraceResponse();
        r.setId(trace.getId());
        r.setTaskId(trace.getTaskId());
        r.setAgentSessionId(trace.getAgentSessionId());
        r.setSessionIndex(trace.getSessionIndex());
        r.setNodeName(trace.getNodeName());
        r.setStatus(trace.getStatus() != null ? trace.getStatus().name() : null);
        r.setPhase(trace.getPhase());
        r.setRetryIndex(trace.getRetryIndex());
        r.setTurnIndex(trace.getTurnIndex());
        r.setTargetChatIndex(trace.getTargetChatIndex());
        r.setInputSnapshot(trace.getInputSnapshot());
        r.setOutputSnapshot(trace.getOutputSnapshot());
        r.setVerdict(trace.getVerdict());
        r.setEvidence(trace.getEvidence());
        r.setErrorMessage(trace.getErrorMessage());
        r.setDurationMs(trace.getDurationMs());
        r.setCreatedAt(trace.getCreatedAt());
        return r;
    }
}
