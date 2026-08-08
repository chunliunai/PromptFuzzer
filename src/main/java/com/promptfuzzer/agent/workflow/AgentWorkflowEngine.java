package com.promptfuzzer.agent.workflow;

import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.repository.AgentSessionRepository;
import com.promptfuzzer.repository.TaskRepository;
import com.promptfuzzer.service.AgentWorkflowTraceRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level workflow entrypoint.
 *
 * Owns the Agent session lifecycle and records its top-level trace.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWorkflowEngine {

    private final AgentSessionRepository agentSessionRepository;
    private final TaskRepository taskRepository;
    private final AgentWorkflowTraceRecorder traceRecorder;
    private final AgentWorkflowSessionRunner sessionRunner;
    private final AgentWorkflowRuntime runtime;

    public List<String> nodeNames() {
        return runtime.nodeNames();
    }

    public void executeSession(Long sessionId, Long existingBrowserSessionId) {
        long startedAt = System.currentTimeMillis();
        AgentSession session = agentSessionRepository.findById(sessionId).orElse(null);
        Task task = session != null ? taskRepository.findById(session.getTaskId()).orElse(null) : null;
        Map<String, Object> input = mapOf(
                "sessionId", sessionId,
                "existingBrowserSessionId", existingBrowserSessionId,
                "nodes", nodeNames()
        );
        try {
            log.info("Agent workflow engine taking over session {} with nodes={}", sessionId, nodeNames());
            sessionRunner.runSession(sessionId, existingBrowserSessionId);
            AgentSession refreshed = agentSessionRepository.findById(sessionId).orElse(session);
            traceRecorder.success(task, refreshed, "WORKFLOW_ENGINE", refreshed != null ? refreshed.getCurrentPhase() : null,
                    refreshed != null ? refreshed.getCurrentRetry() : null,
                    refreshed != null ? refreshed.getCurrentTurn() : null,
                    refreshed != null ? refreshed.getTargetChatIndex() : null,
                    input,
                    mapOf("status", refreshed != null ? refreshed.getStatus() : null,
                            "finalVerdict", refreshed != null ? refreshed.getFinalVerdict() : null),
                    refreshed != null && refreshed.getFinalVerdict() != null ? refreshed.getFinalVerdict().name() : null,
                    refreshed != null ? refreshed.getFinalEvidence() : null,
                    startedAt);
        } catch (Exception e) {
            traceRecorder.error(task, session, "WORKFLOW_ENGINE", session != null ? session.getCurrentPhase() : null,
                    session != null ? session.getCurrentRetry() : null,
                    session != null ? session.getCurrentTurn() : null,
                    session != null ? session.getTargetChatIndex() : null,
                    input, null, null, "Workflow Engine 执行异常", e, startedAt);
            throw e;
        }
    }

    private Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }

}
