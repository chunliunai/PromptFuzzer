package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.AgentWorkflowTrace;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.repository.AgentWorkflowTraceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentWorkflowTraceRecorder {

    private static final int SNAPSHOT_MAX_CHARS = 6000;
    private static final int TEXT_MAX_CHARS = 2000;

    private final AgentWorkflowTraceRepository traceRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${agent.workflow.trace.enabled:true}")
    private boolean traceEnabled;

    public void success(Task task, AgentSession session, String nodeName,
                        String phase, Integer retryIndex, Integer turnIndex, Integer targetChatIndex,
                        Object inputSnapshot, Object outputSnapshot,
                        String verdict, String evidence, long startedAtMs) {
        record(task, session, nodeName, AgentWorkflowTrace.NodeStatus.SUCCESS, phase, retryIndex, turnIndex,
                targetChatIndex, inputSnapshot, outputSnapshot, verdict, evidence, null, startedAtMs);
    }

    public void error(Task task, AgentSession session, String nodeName,
                      String phase, Integer retryIndex, Integer turnIndex, Integer targetChatIndex,
                      Object inputSnapshot, Object outputSnapshot,
                      String verdict, String evidence, Exception error, long startedAtMs) {
        record(task, session, nodeName, AgentWorkflowTrace.NodeStatus.ERROR, phase, retryIndex, turnIndex,
                targetChatIndex, inputSnapshot, outputSnapshot, verdict, evidence,
                error != null ? error.getMessage() : null, startedAtMs);
    }

    public void skipped(Task task, AgentSession session, String nodeName,
                        String phase, Integer retryIndex, Integer turnIndex, Integer targetChatIndex,
                        Object inputSnapshot, String reason, long startedAtMs) {
        record(task, session, nodeName, AgentWorkflowTrace.NodeStatus.SKIPPED, phase, retryIndex, turnIndex,
                targetChatIndex, inputSnapshot, null, null, reason, null, startedAtMs);
    }

    private void record(Task task, AgentSession session, String nodeName, AgentWorkflowTrace.NodeStatus status,
                        String phase, Integer retryIndex, Integer turnIndex, Integer targetChatIndex,
                        Object inputSnapshot, Object outputSnapshot,
                        String verdict, String evidence, String errorMessage, long startedAtMs) {
        if (!traceEnabled || task == null || session == null) {
            return;
        }
        try {
            AgentWorkflowTrace trace = new AgentWorkflowTrace();
            trace.setTaskId(task.getId());
            trace.setAgentSessionId(session.getId());
            trace.setSessionIndex(session.getSessionIndex());
            trace.setNodeName(nodeName);
            trace.setStatus(status);
            trace.setPhase(phase);
            trace.setRetryIndex(retryIndex);
            trace.setTurnIndex(turnIndex);
            trace.setTargetChatIndex(targetChatIndex);
            trace.setInputSnapshot(toSnapshot(inputSnapshot));
            trace.setOutputSnapshot(toSnapshot(outputSnapshot));
            trace.setVerdict(truncate(verdict, 128));
            trace.setEvidence(truncate(evidence, TEXT_MAX_CHARS));
            trace.setErrorMessage(truncate(errorMessage, TEXT_MAX_CHARS));
            trace.setDurationMs(Math.max(0, System.currentTimeMillis() - startedAtMs));
            traceRepository.save(trace);
        } catch (Exception e) {
            log.debug("Agent workflow trace record failed for node {}: {}", nodeName, e.getMessage());
        }
    }

    private String toSnapshot(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof String text) {
                return truncate(text, SNAPSHOT_MAX_CHARS);
            }
            return truncate(objectMapper.writeValueAsString(value), SNAPSHOT_MAX_CHARS);
        } catch (Exception e) {
            return truncate(String.valueOf(value), SNAPSHOT_MAX_CHARS);
        }
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars)) + "...";
    }
}
