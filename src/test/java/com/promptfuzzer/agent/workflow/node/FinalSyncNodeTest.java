package com.promptfuzzer.agent.workflow.node;

import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.ScanResult;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.repository.AgentSessionRepository;
import com.promptfuzzer.repository.ScanResultRepository;
import com.promptfuzzer.repository.TaskRepository;
import com.promptfuzzer.service.AgentWorkflowTraceRecorder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinalSyncNodeTest {

    @Test
    void completesTaskWhenAllAgentsAreTerminal() {
        AgentSessionRepository sessionRepository = mock(AgentSessionRepository.class);
        ScanResultRepository scanRepository = mock(ScanResultRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        AgentWorkflowTraceRecorder traceRecorder = mock(AgentWorkflowTraceRecorder.class);
        FinalSyncNode node = new FinalSyncNode(
                sessionRepository, scanRepository, taskRepository, traceRecorder);

        Task task = task(12L);
        AgentSession current = session(34L, AgentSession.SessionStatus.BLOCKED,
                AgentSession.Verdict.UNCERTAIN);
        current.setTaskId(12L);
        AgentSession successful = session(35L, AgentSession.SessionStatus.SUCCESS,
                AgentSession.Verdict.SUCCESS);
        when(sessionRepository.findByTaskIdOrderBySessionIndexAsc(12L))
                .thenReturn(List.of(current, successful));
        when(scanRepository.countByTaskIdAndVerdict(12L, ScanResult.Verdict.SUCCESS))
                .thenReturn(1L);
        when(scanRepository.countByTaskIdAndVerdict(12L, ScanResult.Verdict.FAIL))
                .thenReturn(2L);
        when(scanRepository.countByTaskIdAndVerdict(12L, ScanResult.Verdict.UNCERTAIN))
                .thenReturn(3L);

        node.execute(context(task, current), state(), List.of(Map.of("turn", 1)));

        assertEquals(Task.TaskStatus.COMPLETED, task.getStatus());
        assertEquals(8, task.getTotalCount());
        assertEquals(2, task.getSuccessCount());
        assertEquals(2, task.getFailCount());
        assertEquals(4, task.getUncertainCount());
        verify(sessionRepository).save(current);
        verify(taskRepository).save(task);
    }

    @Test
    void leavesTaskRunningWhenAnotherAgentIsNotTerminal() {
        AgentSessionRepository sessionRepository = mock(AgentSessionRepository.class);
        ScanResultRepository scanRepository = mock(ScanResultRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        FinalSyncNode node = new FinalSyncNode(sessionRepository, scanRepository,
                taskRepository, mock(AgentWorkflowTraceRecorder.class));

        Task task = task(12L);
        task.setStatus(Task.TaskStatus.RUNNING);
        AgentSession current = session(34L, AgentSession.SessionStatus.BLOCKED,
                AgentSession.Verdict.FAIL);
        current.setTaskId(12L);
        AgentSession running = session(35L, AgentSession.SessionStatus.RUNNING, null);
        when(sessionRepository.findByTaskIdOrderBySessionIndexAsc(12L))
                .thenReturn(List.of(current, running));

        node.execute(context(task, current), state(), List.of());

        assertEquals(Task.TaskStatus.RUNNING, task.getStatus());
        verify(taskRepository, never()).save(any());
        verify(scanRepository, never())
                .countByTaskIdAndVerdict(any(), any());
    }

    private Task task(Long id) {
        Task task = new Task();
        task.setId(id);
        return task;
    }

    private AgentSession session(Long id, AgentSession.SessionStatus status,
                                 AgentSession.Verdict verdict) {
        AgentSession session = new AgentSession();
        session.setId(id);
        session.setSessionIndex(id.intValue());
        session.setStatus(status);
        session.setFinalVerdict(verdict);
        return session;
    }

    private AgentWorkflowContext context(Task task, AgentSession session) {
        return AgentWorkflowContext.builder().task(task).session(session).build();
    }

    private AgentWorkflowState state() {
        return AgentWorkflowState.builder()
                .phase("ATTACK")
                .retryIndex(0)
                .turnIndex(2)
                .targetChatIndex(1)
                .build();
    }
}
