package com.promptfuzzer.service;

import com.promptfuzzer.dto.CreateTaskRequest;
import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.ReconAttackCandidate;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.repository.AgentStrategySnapshotRepository;
import com.promptfuzzer.repository.AgentSessionRepository;
import com.promptfuzzer.repository.AgentWorkflowTraceRepository;
import com.promptfuzzer.repository.OobTargetRepository;
import com.promptfuzzer.repository.ReconAttackCandidateRepository;
import com.promptfuzzer.repository.ScanPayloadRepository;
import com.promptfuzzer.repository.ScanResultRepository;
import com.promptfuzzer.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskServiceReconIntegrationTest {

    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final AgentSessionRepository sessionRepository =
            mock(AgentSessionRepository.class);
    private final ScanPayloadRepository payloadRepository =
            mock(ScanPayloadRepository.class);
    private final ScanResultRepository resultRepository =
            mock(ScanResultRepository.class);
    private final OobTargetRepository oobTargetRepository =
            mock(OobTargetRepository.class);
    private final AgentWorkflowTraceRepository traceRepository =
            mock(AgentWorkflowTraceRepository.class);
    private final AgentStrategySnapshotRepository snapshotRepository =
            mock(AgentStrategySnapshotRepository.class);
    private final ReconAttackCandidateRepository candidateRepository =
            mock(ReconAttackCandidateRepository.class);
    private final AgentSessionExecutor sessionExecutor =
            mock(AgentSessionExecutor.class);
    private final ReconIntelligenceService reconIntelligenceService =
            mock(ReconIntelligenceService.class);
    private final TaskService service = new TaskService(
            taskRepository,
            payloadRepository,
            resultRepository,
            sessionRepository,
            oobTargetRepository,
            traceRepository,
            snapshotRepository,
            candidateRepository,
            mock(ScanExecutorService.class),
            sessionExecutor,
            mock(AutoModeService.class),
            mock(PayloadGeneratorService.class),
            mock(OobService.class),
            reconIntelligenceService);

    @Test
    void deletesCompletedTaskAndResetsExecutedCandidate() {
        Task task = new Task();
        task.setId(30L);
        task.setStatus(Task.TaskStatus.COMPLETED);
        ReconAttackCandidate candidate = new ReconAttackCandidate();
        candidate.setTaskId(30L);
        candidate.setStatus(ReconAttackCandidate.CandidateStatus.EXECUTED);
        when(taskRepository.findById(30L)).thenReturn(java.util.Optional.of(task));
        when(candidateRepository.findByTaskId(30L)).thenReturn(List.of(candidate));

        service.deleteTask(30L);

        assertEquals(null, candidate.getTaskId());
        assertEquals(ReconAttackCandidate.CandidateStatus.DRAFT, candidate.getStatus());
        verify(candidateRepository).saveAll(List.of(candidate));
        verify(traceRepository).deleteByTaskId(30L);
        verify(snapshotRepository).deleteByTaskId(30L);
        verify(sessionRepository).deleteByTaskId(30L);
        verify(resultRepository).deleteByTaskId(30L);
        verify(payloadRepository).deleteByTaskId(30L);
        verify(oobTargetRepository).deleteByTaskId(30L);
        verify(taskRepository).delete(task);
    }

    @Test
    void rejectsDeletingRunningTask() {
        Task task = new Task();
        task.setId(31L);
        task.setStatus(Task.TaskStatus.RUNNING);
        when(taskRepository.findById(31L)).thenReturn(java.util.Optional.of(task));

        assertThrows(IllegalArgumentException.class, () -> service.deleteTask(31L));
    }

    @Test
    void agentTaskLoadsReconAndInitializesCreatedSession() {
        when(reconIntelligenceService.loadTargetIntelligenceMemory(12L, null))
                .thenReturn("recon-memory");
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            task.setId(30L);
            return task;
        });
        when(sessionRepository.saveAll(any())).thenAnswer(invocation -> {
            List<AgentSession> sessions = invocation.getArgument(0);
            sessions.get(0).setId(40L);
            return sessions;
        });

        CreateTaskRequest request = baseAgentRequest();
        request.setReconResultId(12L);

        service.createTask(request);

        ArgumentCaptor<AgentSession> sessionCaptor =
                ArgumentCaptor.forClass(AgentSession.class);
        verify(reconIntelligenceService).initializeSession(
                sessionCaptor.capture(), any(Task.class), any(String.class));
        assertEquals(30L, sessionCaptor.getValue().getTaskId());
        verify(sessionExecutor).executeSession(40L);
    }

    @Test
    void agentTaskPersistsSelectedSurfaceIdsAndLoadsSelectedReconMemory() {
        when(reconIntelligenceService.loadTargetIntelligenceMemory(
                eq(12L), eq(List.of(21L, 22L))))
                .thenReturn("selected-recon-memory");
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            task.setId(30L);
            return task;
        });
        when(sessionRepository.saveAll(any())).thenAnswer(invocation -> {
            List<AgentSession> sessions = invocation.getArgument(0);
            sessions.get(0).setId(40L);
            return sessions;
        });

        CreateTaskRequest request = baseAgentRequest();
        request.setReconResultId(12L);
        request.setSelectedSurfaceIds(List.of(21L, 22L));

        service.createTask(request);

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository, org.mockito.Mockito.atLeastOnce())
                .save(taskCaptor.capture());
        assertEquals("[21,22]", taskCaptor.getAllValues().get(0)
                .getSelectedSurfaceIds());
        verify(reconIntelligenceService).loadTargetIntelligenceMemory(
                12L, List.of(21L, 22L));
    }

    @Test
    void rejectsReconReferenceForSingleTurnTask() {
        CreateTaskRequest request = baseAgentRequest();
        request.setAttackMode("SINGLE_TURN");
        request.setReconResultId(12L);

        assertThrows(IllegalArgumentException.class,
                () -> service.createTask(request));
    }

    @Test
    void rejectsSupplementalReconWithoutReconResult() {
        CreateTaskRequest request = baseAgentRequest();
        request.setSupplementalRecon(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.createTask(request));
    }

    @Test
    void rejectsSelectedSurfacesWithoutReconResult() {
        CreateTaskRequest request = baseAgentRequest();
        request.setSelectedSurfaceIds(List.of(21L));

        assertThrows(IllegalArgumentException.class,
                () -> service.createTask(request));
    }

    @Test
    void rejectsSingleTurnWithoutGoalAndTechnique() {
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            task.setId(30L);
            return task;
        });
        CreateTaskRequest request = new CreateTaskRequest();
        request.setName("Invalid single turn");
        request.setScanMode("HTTP_TEMPLATE");
        request.setAttackMode("SINGLE_TURN");

        assertThrows(IllegalArgumentException.class,
                () -> service.createTask(request));
    }

    private CreateTaskRequest baseAgentRequest() {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setName("Agent task with recon");
        request.setScanMode("HTTP_TEMPLATE");
        request.setAttackMode("AGENT");
        request.setGoalIds(List.of("goal"));
        request.setTechniqueIds(List.of("technique"));
        request.setGenerateCount(1);
        return request;
    }
}
