package com.promptfuzzer.controller;

import com.promptfuzzer.dto.AgentSessionResponse;
import com.promptfuzzer.dto.AgentWorkflowTraceResponse;
import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.repository.AgentSessionRepository;
import com.promptfuzzer.repository.AgentStrategySnapshotRepository;
import com.promptfuzzer.repository.AgentWorkflowTraceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tasks/{taskId}/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentSessionRepository agentSessionRepository;
    private final AgentStrategySnapshotRepository strategySnapshotRepository;
    private final AgentWorkflowTraceRepository workflowTraceRepository;

    @GetMapping
    public ResponseEntity<AgentListResponse> listAgents(@PathVariable Long taskId) {
        List<AgentSession> sessions = agentSessionRepository.findByTaskIdOrderBySessionIndexAsc(taskId);
        List<AgentSessionResponse> agents = sessions.stream()
                .map(AgentSessionResponse::summary)
                .collect(Collectors.toList());

        int successCount = (int) sessions.stream()
                .filter(s -> s.getStatus() == AgentSession.SessionStatus.SUCCESS).count();

        AgentListResponse response = new AgentListResponse();
        response.setTaskId(taskId);
        response.setTotalAgents(agents.size());
        response.setSuccessCount(successCount);
        response.setAgents(agents);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{sessionIndex}")
    public ResponseEntity<AgentSessionResponse> getAgent(
            @PathVariable Long taskId, @PathVariable int sessionIndex) {
        AgentSession session = agentSessionRepository
                .findByTaskIdAndSessionIndex(taskId, sessionIndex);
        if (session == null) {
            throw new RuntimeException("AgentSession not found: taskId=" + taskId + " sessionIndex=" + sessionIndex);
        }
        AgentSessionResponse response = AgentSessionResponse.detail(session);
        response.setStrategySnapshots(strategySnapshotRepository
                .findByAgentSessionIdOrderByIdAsc(session.getId())
                .stream()
                .map(AgentSessionResponse.StrategySnapshotRecord::from)
                .collect(Collectors.toList()));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{sessionIndex}/traces")
    public ResponseEntity<List<AgentWorkflowTraceResponse>> getAgentTraces(
            @PathVariable Long taskId, @PathVariable int sessionIndex) {
        AgentSession session = agentSessionRepository
                .findByTaskIdAndSessionIndex(taskId, sessionIndex);
        if (session == null) {
            throw new RuntimeException("AgentSession not found: taskId=" + taskId + " sessionIndex=" + sessionIndex);
        }
        List<AgentWorkflowTraceResponse> traces = workflowTraceRepository
                .findByAgentSessionIdOrderByIdAsc(session.getId())
                .stream()
                .map(AgentWorkflowTraceResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(traces);
    }

    @lombok.Data
    public static class AgentListResponse {
        private Long taskId;
        private int totalAgents;
        private int successCount;
        private List<AgentSessionResponse> agents;
    }
}
