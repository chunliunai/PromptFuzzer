package com.promptfuzzer.agent.workflow.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowNode;
import com.promptfuzzer.agent.workflow.AgentWorkflowNodes;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.ScanResult;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.repository.AgentSessionRepository;
import com.promptfuzzer.repository.ScanResultRepository;
import com.promptfuzzer.repository.TaskRepository;
import com.promptfuzzer.service.AgentWorkflowTraceRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class FinalSyncNode implements AgentWorkflowNode {

    private final AgentSessionRepository agentSessionRepository;
    private final ScanResultRepository scanResultRepository;
    private final TaskRepository taskRepository;
    private final AgentWorkflowTraceRecorder traceRecorder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return AgentWorkflowNodes.FINAL_SYNC;
    }

    public void execute(AgentWorkflowContext context,
                        AgentWorkflowState state,
                        List<Map<String, Object>> allTurnRecords) {
        long startedAt = System.currentTimeMillis();
        Task task = context.getTask();
        AgentSession session = context.getSession();
        try {
            session.setConversationHistory(objectMapper.writeValueAsString(allTurnRecords));
            agentSessionRepository.save(session);
            syncTaskStatus(task);
            traceRecorder.success(task, session, name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    mapOf("turns", allTurnRecords.size()),
                    mapOf("status", session.getStatus(),
                            "finalVerdict", session.getFinalVerdict(),
                            "finalEvidence", session.getFinalEvidence()),
                    session.getFinalVerdict() != null ? session.getFinalVerdict().name() : null,
                    session.getFinalEvidence(), startedAt);
        } catch (Exception e) {
            traceRecorder.error(task, session, name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    mapOf("turns", allTurnRecords.size()), null,
                    session.getFinalVerdict() != null ? session.getFinalVerdict().name() : null,
                    session.getFinalEvidence(), e, startedAt);
            throw new IllegalStateException("Final sync failed", e);
        }
    }

    private void syncTaskStatus(Task task) {
        List<AgentSession> allSessions = agentSessionRepository
                .findByTaskIdOrderBySessionIndexAsc(task.getId());
        boolean allDone = allSessions.stream()
                .allMatch(session -> session.getStatus() == AgentSession.SessionStatus.SUCCESS
                        || session.getStatus() == AgentSession.SessionStatus.BLOCKED
                        || session.getStatus() == AgentSession.SessionStatus.ERROR);
        if (!allDone) {
            return;
        }

        long scanSuccessCount = scanResultRepository.countByTaskIdAndVerdict(
                task.getId(), ScanResult.Verdict.SUCCESS);
        long scanFailCount = scanResultRepository.countByTaskIdAndVerdict(
                task.getId(), ScanResult.Verdict.FAIL);
        long scanUncertainCount = scanResultRepository.countByTaskIdAndVerdict(
                task.getId(), ScanResult.Verdict.UNCERTAIN);
        long scanTotalCount = scanSuccessCount + scanFailCount + scanUncertainCount;

        long agentSuccessCount = allSessions.stream()
                .filter(session -> session.getStatus() == AgentSession.SessionStatus.SUCCESS)
                .count();
        long agentUncertainCount = allSessions.stream()
                .filter(session -> session.getStatus() == AgentSession.SessionStatus.BLOCKED
                        && session.getFinalVerdict() == AgentSession.Verdict.UNCERTAIN)
                .count();
        long agentFailCount = allSessions.stream()
                .filter(session -> session.getStatus() == AgentSession.SessionStatus.BLOCKED
                        && session.getFinalVerdict() != AgentSession.Verdict.UNCERTAIN)
                .count();
        long agentErrorCount = allSessions.stream()
                .filter(session -> session.getStatus() == AgentSession.SessionStatus.ERROR)
                .count();

        task.setTotalCount((int) (scanTotalCount + allSessions.size()));
        task.setSuccessCount((int) (scanSuccessCount + agentSuccessCount));
        task.setFailCount((int) (scanFailCount + agentFailCount + agentErrorCount));
        task.setUncertainCount((int) (scanUncertainCount + agentUncertainCount));
        task.setStatus(Task.TaskStatus.COMPLETED);
        taskRepository.save(task);

        log.info("Task {} synced by workflow: scan(S/F/U)={}/{}/{}, agent(S/F/U/E)={}/{}/{}/{}",
                task.getId(), scanSuccessCount, scanFailCount, scanUncertainCount,
                agentSuccessCount, agentFailCount, agentUncertainCount, agentErrorCount);
    }

    private Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }
}
