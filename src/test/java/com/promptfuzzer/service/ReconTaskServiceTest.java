package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.config.BrowserTargetConfig;
import com.promptfuzzer.dto.CreateReconTaskRequest;
import com.promptfuzzer.dto.ReconApplicationSurfaceResponse;
import com.promptfuzzer.dto.UpdateReconApplicationSurfaceRequest;
import com.promptfuzzer.entity.ReconApplicationSurface;
import com.promptfuzzer.entity.ReconResult;
import com.promptfuzzer.entity.ReconTask;
import com.promptfuzzer.repository.ReconApplicationSurfaceRepository;
import com.promptfuzzer.repository.ReconResultRepository;
import com.promptfuzzer.repository.ReconTaskRepository;
import com.promptfuzzer.repository.ReconTraceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReconTaskServiceTest {

    @Test
    void createsBrowserTaskAndLaunchesAsyncExecutor() {
        ReconTaskRepository taskRepository = mock(ReconTaskRepository.class);
        ReconExecutor executor = mock(ReconExecutor.class);
        ReconTaskService service = service(taskRepository,
                mock(ReconResultRepository.class),
                mock(ReconApplicationSurfaceRepository.class), executor);
        when(taskRepository.save(any(ReconTask.class))).thenAnswer(invocation -> {
            ReconTask task = invocation.getArgument(0);
            task.setId(45L);
            return task;
        });

        CreateReconTaskRequest request = new CreateReconTaskRequest();
        request.setName("Agent capability recon");
        request.setReconContext("Discover supported work and tools");
        BrowserTargetConfig targetConfig = new BrowserTargetConfig();
        targetConfig.setChatUrl("https://example.com/chat");
        targetConfig.setTargetType("example");
        targetConfig.setSelectors(Map.of("input", "#input"));
        request.setTargetConfig(targetConfig);
        request.getReconConfig().setMaxCapabilityVerifications(10);

        service.createTask(request);

        ArgumentCaptor<ReconTask> taskCaptor = ArgumentCaptor.forClass(ReconTask.class);
        verify(taskRepository).save(taskCaptor.capture());
        verify(executor).execute(45L);
        assertEquals(ReconTask.Status.PENDING, taskCaptor.getValue().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                taskCaptor.getValue().getReconConfig()
                        .contains("\"maxCapabilityVerifications\":8"));
        org.junit.jupiter.api.Assertions.assertTrue(
                taskCaptor.getValue().getReconConfig()
                        .contains("\"openDiscoveryEnabled\":true"));
        org.junit.jupiter.api.Assertions.assertTrue(
                taskCaptor.getValue().getReconConfig()
                        .contains("\"maxOpenDiscoveryMessages\":1"));
    }

    @Test
    void createsBrowserTaskWithoutSelectorsForAutoDetection() {
        ReconTaskRepository taskRepository = mock(ReconTaskRepository.class);
        ReconExecutor executor = mock(ReconExecutor.class);
        ReconTaskService service = service(taskRepository,
                mock(ReconResultRepository.class),
                mock(ReconApplicationSurfaceRepository.class), executor);
        when(taskRepository.save(any(ReconTask.class))).thenAnswer(invocation -> {
            ReconTask task = invocation.getArgument(0);
            task.setId(46L);
            return task;
        });

        CreateReconTaskRequest request = new CreateReconTaskRequest();
        request.setName("Auto-adapted browser recon");
        BrowserTargetConfig targetConfig = new BrowserTargetConfig();
        targetConfig.setChatUrl("https://example.com/chat");
        targetConfig.setTargetType("default");
        request.setTargetConfig(targetConfig);

        service.createTask(request);

        ArgumentCaptor<ReconTask> taskCaptor = ArgumentCaptor.forClass(ReconTask.class);
        verify(taskRepository).save(taskCaptor.capture());
        verify(executor).execute(46L);
        org.junit.jupiter.api.Assertions.assertTrue(
                taskCaptor.getValue().getTargetConfig().contains("\"selectors\":null"));
    }

    @Test
    void rejectsBrowserTaskWithoutTargetConfig() {
        ReconTaskService service = service(
                mock(ReconTaskRepository.class),
                mock(ReconResultRepository.class),
                mock(ReconApplicationSurfaceRepository.class),
                mock(ReconExecutor.class));
        CreateReconTaskRequest request = new CreateReconTaskRequest();
        request.setName("invalid");

        assertThrows(IllegalArgumentException.class, () -> service.createTask(request));
    }

    @Test
    void createsHttpTemplateTask() {
        ReconTaskRepository taskRepository = mock(ReconTaskRepository.class);
        ReconExecutor executor = mock(ReconExecutor.class);
        ReconTaskService service = service(
                taskRepository,
                mock(ReconResultRepository.class),
                mock(ReconApplicationSurfaceRepository.class),
                executor);
        when(taskRepository.save(any(ReconTask.class))).thenAnswer(invocation -> {
            ReconTask task = invocation.getArgument(0);
            task.setId(46L);
            return task;
        });

        CreateReconTaskRequest request = new CreateReconTaskRequest();
        request.setName("HTTP agent recon");
        request.setScanMode("HTTP_TEMPLATE");
        request.setRawRequestTemplate("""
                POST /api/chat HTTP/1.1
                Host: localhost:18080
                X-Target-Scheme: http
                Content-Type: application/json

                {"message":"{{PAYLOAD}}"}
                """);

        service.createTask(request);

        ArgumentCaptor<ReconTask> captor = ArgumentCaptor.forClass(ReconTask.class);
        verify(taskRepository).save(captor.capture());
        assertEquals(ReconTask.ScanMode.HTTP_TEMPLATE, captor.getValue().getScanMode());
        verify(executor).execute(46L);
    }

    @Test
    void rejectsHttpTemplateWithoutPayloadPlaceholder() {
        ReconTaskService service = service(
                mock(ReconTaskRepository.class),
                mock(ReconResultRepository.class),
                mock(ReconApplicationSurfaceRepository.class),
                mock(ReconExecutor.class));
        CreateReconTaskRequest request = new CreateReconTaskRequest();
        request.setName("invalid HTTP recon");
        request.setScanMode("HTTP_TEMPLATE");
        request.setRawRequestTemplate("""
                POST /api/chat HTTP/1.1
                Host: example.com

                {"message":"fixed"}
                """);

        assertThrows(IllegalArgumentException.class, () -> service.createTask(request));
    }

    @Test
    void userEditPreservesOriginalApplicationSurface() {
        ReconResultRepository resultRepository = mock(ReconResultRepository.class);
        ReconApplicationSurfaceRepository surfaceRepository =
                mock(ReconApplicationSurfaceRepository.class);
        ReconTaskService service = service(
                mock(ReconTaskRepository.class), resultRepository,
                surfaceRepository, mock(ReconExecutor.class));

        ReconResult result = new ReconResult();
        result.setId(7L);
        when(resultRepository.findById(7L)).thenReturn(Optional.of(result));

        ReconApplicationSurface surface = new ReconApplicationSurface();
        surface.setId(9L);
        surface.setReconResultId(7L);
        surface.setOriginalTitle("支持代码能力");
        surface.setCurrentTitle("支持代码能力");
        when(surfaceRepository.findById(9L)).thenReturn(Optional.of(surface));
        when(surfaceRepository.save(any(ReconApplicationSurface.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UpdateReconApplicationSurfaceRequest request =
                new UpdateReconApplicationSurfaceRequest();
        request.setTitle("支持 Python 脚本编写");
        request.setVerificationStatus("VERIFIED");

        ReconApplicationSurfaceResponse response =
                service.updateApplicationSurface(7L, 9L, request);

        assertEquals("支持代码能力", surface.getOriginalTitle());
        assertEquals("支持 Python 脚本编写", response.getTitle());
        assertEquals(ReconApplicationSurface.VerificationStatus.VERIFIED,
                surface.getVerificationStatus());
        assertEquals(true, surface.getUserEdited());
    }

    @Test
    void deletesCompletedReconTaskAndAllOwnedArtifacts() {
        ReconTaskRepository taskRepository = mock(ReconTaskRepository.class);
        ReconResultRepository resultRepository = mock(ReconResultRepository.class);
        ReconTraceRepository traceRepository = mock(ReconTraceRepository.class);
        ReconApplicationSurfaceRepository surfaceRepository =
                mock(ReconApplicationSurfaceRepository.class);
        com.promptfuzzer.repository.ReconAttackUnitRepository unitRepository =
                mock(com.promptfuzzer.repository.ReconAttackUnitRepository.class);
        com.promptfuzzer.repository.ReconAttackCandidateRepository candidateRepository =
                mock(com.promptfuzzer.repository.ReconAttackCandidateRepository.class);
        ReconTaskService service = new ReconTaskService(
                taskRepository,
                resultRepository,
                traceRepository,
                surfaceRepository,
                unitRepository,
                candidateRepository,
                mock(ReconExecutor.class),
                new ObjectMapper());
        ReconTask task = new ReconTask();
        task.setId(45L);
        task.setStatus(ReconTask.Status.COMPLETED);
        ReconResult result = new ReconResult();
        result.setId(12L);
        result.setReconTaskId(45L);
        when(taskRepository.findById(45L)).thenReturn(Optional.of(task));
        when(resultRepository.findByReconTaskId(45L)).thenReturn(Optional.of(result));

        service.deleteTask(45L);

        verify(candidateRepository).deleteByReconResultId(12L);
        verify(unitRepository).deleteByReconResultId(12L);
        verify(surfaceRepository).deleteByReconResultId(12L);
        verify(resultRepository).delete(result);
        verify(traceRepository).deleteByReconTaskId(45L);
        verify(taskRepository).delete(task);
    }

    private ReconTaskService service(
            ReconTaskRepository taskRepository,
            ReconResultRepository resultRepository,
            ReconApplicationSurfaceRepository surfaceRepository,
            ReconExecutor executor) {
        return new ReconTaskService(
                taskRepository,
                resultRepository,
                mock(ReconTraceRepository.class),
                surfaceRepository,
                mock(com.promptfuzzer.repository.ReconAttackUnitRepository.class),
                mock(com.promptfuzzer.repository.ReconAttackCandidateRepository.class),
                executor,
                new ObjectMapper());
    }
}
