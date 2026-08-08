package com.promptfuzzer.agent.workflow;

import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.repository.AgentSessionRepository;
import com.promptfuzzer.repository.TaskRepository;
import com.promptfuzzer.service.AgentWorkflowTraceRecorder;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWorkflowEngineTest {

    @Test
    void enabledEngineInvokesSessionRunnerDirectly() {
        AgentSessionRepository sessionRepository = mock(AgentSessionRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        AgentWorkflowTraceRecorder traceRecorder = mock(AgentWorkflowTraceRecorder.class);
        AgentWorkflowSessionRunner sessionRunner = mock(AgentWorkflowSessionRunner.class);
        AgentWorkflowEngine engine = engine(
                sessionRepository, taskRepository, traceRecorder, sessionRunner);

        AgentSession session = new AgentSession();
        session.setId(20L);
        session.setTaskId(10L);
        Task task = new Task();
        task.setId(10L);
        when(sessionRepository.findById(20L)).thenReturn(Optional.of(session));
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));

        engine.executeSession(20L, 30L);

        verify(sessionRunner).runSession(20L, 30L);
        verify(traceRecorder).success(
                org.mockito.ArgumentMatchers.eq(task),
                org.mockito.ArgumentMatchers.eq(session),
                org.mockito.ArgumentMatchers.eq("WORKFLOW_ENGINE"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private AgentWorkflowEngine engine(AgentSessionRepository sessionRepository,
                                       TaskRepository taskRepository,
                                       AgentWorkflowTraceRecorder traceRecorder,
                                       AgentWorkflowSessionRunner sessionRunner) {
        return new AgentWorkflowEngine(
                sessionRepository,
                taskRepository,
                traceRecorder,
                sessionRunner,
                mock(AgentWorkflowRuntime.class));
    }
}
