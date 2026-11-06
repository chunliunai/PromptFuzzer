package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.config.BrowserTargetConfig;
import com.promptfuzzer.dto.CreateReconTaskRequest;
import com.promptfuzzer.dto.ReconConfig;
import com.promptfuzzer.dto.ReconApplicationSurfaceResponse;
import com.promptfuzzer.dto.UpdateReconApplicationSurfaceRequest;
import com.promptfuzzer.entity.ReconApplicationSurface;
import com.promptfuzzer.entity.ReconResult;
import com.promptfuzzer.entity.ReconTask;
import com.promptfuzzer.repository.ReconApplicationSurfaceRepository;
import com.promptfuzzer.repository.ReconResultRepository;
import com.promptfuzzer.repository.ReconTaskRepository;
import com.promptfuzzer.repository.ReconTraceRepository;
import com.promptfuzzer.repository.ReplayProfileRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
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
        org.junit.jupiter.api.Assertions.assertTrue(
                taskCaptor.getValue().getReconConfig()
                        .contains("\"targetExecutionMode\":\"AUTO\""));
    }

    @Test
    void acceptsHigherCapabilityLimitWhenServerLimitIsRaised() {
        ReconTaskRepository taskRepository = mock(ReconTaskRepository.class);
        ReconTaskService service = service(
                taskRepository,
                mock(ReconResultRepository.class),
                mock(ReconApplicationSurfaceRepository.class),
                mock(ReconExecutor.class));
        ReflectionTestUtils.setField(
                service, "maxCapabilityVerifications", 32);
        when(taskRepository.save(any(ReconTask.class))).thenAnswer(invocation -> {
            ReconTask task = invocation.getArgument(0);
            task.setId(47L);
            return task;
        });

        CreateReconTaskRequest request = browserRequest("benchmark");
        request.getReconConfig().setMaxCapabilityVerifications(10);
        request.getReconConfig().setTargetExecutionMode(
                ReconConfig.TargetExecutionMode.BROWSER_ONLY);
        request.getReconConfig().setTaskTimeoutSeconds(120);

        service.createTask(request);

        ArgumentCaptor<ReconTask> captor =
                ArgumentCaptor.forClass(ReconTask.class);
        verify(taskRepository).save(captor.capture());
        String config = captor.getValue().getReconConfig();
        org.junit.jupiter.api.Assertions.assertTrue(
                config.contains("\"maxCapabilityVerifications\":10"));
        org.junit.jupiter.api.Assertions.assertTrue(
                config.contains("\"targetExecutionMode\":\"BROWSER_ONLY\""));
        org.junit.jupiter.api.Assertions.assertTrue(
                config.contains("\"taskTimeoutSeconds\":120"));
    }

    @Test
    void clampsCapabilityLimitToRaisedServerLimit() {
        ReconTaskRepository taskRepository = mock(ReconTaskRepository.class);
        ReconTaskService service = service(
                taskRepository,
                mock(ReconResultRepository.class),
                mock(ReconApplicationSurfaceRepository.class),
                mock(ReconExecutor.class));
        ReflectionTestUtils.setField(
                service, "maxCapabilityVerifications", 32);
        when(taskRepository.save(any(ReconTask.class))).thenAnswer(invocation -> {
            ReconTask task = invocation.getArgument(0);
            task.setId(48L);
            return task;
        });

        CreateReconTaskRequest request = browserRequest("bounded benchmark");
        request.getReconConfig().setMaxCapabilityVerifications(100);

        service.createTask(request);

        ArgumentCaptor<ReconTask> captor =
                ArgumentCaptor.forClass(ReconTask.class);
        verify(taskRepository).save(captor.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                captor.getValue().getReconConfig()
                        .contains("\"maxCapabilityVerifications\":32"));
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
    void userEditPreservesOriginalAndUnsubmittedStructuredFields()
            throws Exception {
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
        surface.setResourceObjects("[\"Python 脚本\"]");
        surface.setEntrypoints("[\"自然语言指令\"]");
        surface.setPermissionBoundaries("[\"workspace\"]");
        surface.setControlBoundaries("[\"sandbox\"]");
        when(surfaceRepository.findById(9L)).thenReturn(Optional.of(surface));
        when(surfaceRepository.save(any(ReconApplicationSurface.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UpdateReconApplicationSurfaceRequest request =
                new UpdateReconApplicationSurfaceRequest();
        request.setTitle("支持 Python 脚本编写");
        request.setVerificationStatus("VERIFIED");
        request.setSupportedActions(new ObjectMapper().readTree(
                "\"[\\\"execute\\\"]\""));

        ReconApplicationSurfaceResponse response =
                service.updateApplicationSurface(7L, 9L, request);

        assertEquals("支持代码能力", surface.getOriginalTitle());
        assertEquals("支持 Python 脚本编写", response.getTitle());
        assertEquals(ReconApplicationSurface.VerificationStatus.VERIFIED,
                surface.getVerificationStatus());
        assertEquals(List.of("execute"), response.getSupportedActions());
        assertEquals(List.of("Python 脚本"), response.getResourceObjects());
        assertEquals(List.of("自然语言指令"), response.getEntrypoints());
        assertEquals(List.of("workspace"), response.getPermissionBoundaries());
        assertEquals(List.of("sandbox"), response.getControlBoundaries());
        assertEquals(true, surface.getUserEdited());
    }

    @Test
    void resultResponseIncludesPersistedApplicationSurfaces() {
        ReconTaskRepository taskRepository = mock(ReconTaskRepository.class);
        ReconResultRepository resultRepository = mock(ReconResultRepository.class);
        ReconApplicationSurfaceRepository surfaceRepository =
                mock(ReconApplicationSurfaceRepository.class);
        ReconTaskService service = service(
                taskRepository, resultRepository,
                surfaceRepository, mock(ReconExecutor.class));

        ReconTask task = new ReconTask();
        task.setId(48L);
        ReconResult result = new ReconResult();
        result.setId(22L);
        result.setReconTaskId(48L);
        result.setQualityStatus(ReconResult.QualityStatus.SUFFICIENT);
        ReconApplicationSurface surface = new ReconApplicationSurface();
        surface.setId(31L);
        surface.setReconResultId(22L);
        surface.setCurrentTitle("业务工具编排边界");
        surface.setCurrentDescription("测试入口：自然语言指令");

        when(taskRepository.findById(48L)).thenReturn(Optional.of(task));
        when(resultRepository.findByReconTaskId(48L)).thenReturn(Optional.of(result));
        when(surfaceRepository.findByReconResultIdAndDeletedFalseOrderByIdAsc(22L))
                .thenReturn(List.of(surface));

        var response = service.getResultByTaskId(48L);

        assertEquals(1, response.getApplicationSurfaces().size());
        assertEquals("业务工具编排边界",
                response.getApplicationSurfaces().get(0).getTitle());
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
        ReplayProfileRepository replayProfileRepository = mock(ReplayProfileRepository.class);
        ReconTaskService service = new ReconTaskService(
                taskRepository,
                resultRepository,
                traceRepository,
                surfaceRepository,
                unitRepository,
                candidateRepository,
                replayProfileRepository,
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
        verify(replayProfileRepository).deleteByTaskId(-45L);
        verify(taskRepository).delete(task);
    }

    @Test
    void rejectsDeletingTaskWhileCancellationIsPending() {
        ReconTaskRepository taskRepository = mock(ReconTaskRepository.class);
        ReconTask task = new ReconTask();
        task.setId(69L);
        task.setStatus(ReconTask.Status.CANCEL_REQUESTED);
        when(taskRepository.findById(69L)).thenReturn(Optional.of(task));
        ReconTaskService service = service(
                taskRepository,
                mock(ReconResultRepository.class),
                mock(ReconApplicationSurfaceRepository.class),
                mock(ReconExecutor.class));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.deleteTask(69L));

        verify(taskRepository, never()).delete(any());
    }

    @Test
    void cancelsPendingTaskIdempotently() {
        ReconTaskRepository taskRepository = mock(ReconTaskRepository.class);
        ReconTask task = new ReconTask();
        task.setId(70L);
        task.setStatus(ReconTask.Status.PENDING);
        when(taskRepository.findById(70L)).thenReturn(Optional.of(task));
        stubStatusTransitions(taskRepository, task);
        ReconTaskService service = service(
                taskRepository,
                mock(ReconResultRepository.class),
                mock(ReconApplicationSurfaceRepository.class),
                mock(ReconExecutor.class));

        assertEquals("CANCELLED", service.cancelTask(70L).getStatus());
        assertEquals("CANCELLED", service.cancelTask(70L).getStatus());

        verify(taskRepository, times(1)).transitionStatus(
                eq(70L),
                eq(List.of(ReconTask.Status.PENDING)),
                eq(ReconTask.Status.CANCELLED),
                isNull(),
                any(),
                any());
    }

    @Test
    void requestsRunningTaskCancellationIdempotently() {
        ReconTaskRepository taskRepository = mock(ReconTaskRepository.class);
        ReconTask task = new ReconTask();
        task.setId(71L);
        task.setStatus(ReconTask.Status.RUNNING);
        when(taskRepository.findById(71L)).thenReturn(Optional.of(task));
        stubStatusTransitions(taskRepository, task);
        ReconTaskService service = service(
                taskRepository,
                mock(ReconResultRepository.class),
                mock(ReconApplicationSurfaceRepository.class),
                mock(ReconExecutor.class));

        assertEquals(
                "CANCEL_REQUESTED", service.cancelTask(71L).getStatus());
        assertEquals(
                "CANCEL_REQUESTED", service.cancelTask(71L).getStatus());

        verify(taskRepository, times(1)).transitionStatus(
                eq(71L),
                eq(List.of(ReconTask.Status.RUNNING)),
                eq(ReconTask.Status.CANCEL_REQUESTED),
                isNull(),
                isNull(),
                any());
    }

    @Test
    void preservesCancellationWhenPendingTaskStartsConcurrently() {
        ReconTaskRepository taskRepository = mock(ReconTaskRepository.class);
        ReconTask task = new ReconTask();
        task.setId(72L);
        task.setStatus(ReconTask.Status.PENDING);
        when(taskRepository.findById(72L)).thenReturn(Optional.of(task));
        when(taskRepository.transitionStatus(
                eq(72L), anyCollection(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<ReconTask.Status> expected =
                            (List<ReconTask.Status>) invocation.getArgument(1);
                    if (expected.contains(ReconTask.Status.PENDING)) {
                        task.setStatus(ReconTask.Status.RUNNING);
                        return 0;
                    }
                    if (expected.contains(ReconTask.Status.RUNNING)) {
                        task.setStatus(ReconTask.Status.CANCEL_REQUESTED);
                        return 1;
                    }
                    return 0;
                });
        ReconTaskService service = service(
                taskRepository,
                mock(ReconResultRepository.class),
                mock(ReconApplicationSurfaceRepository.class),
                mock(ReconExecutor.class));

        assertEquals(
                "CANCEL_REQUESTED", service.cancelTask(72L).getStatus());

        verify(taskRepository).transitionStatus(
                eq(72L),
                eq(List.of(ReconTask.Status.PENDING)),
                eq(ReconTask.Status.CANCELLED),
                isNull(),
                any(),
                any());
        verify(taskRepository).transitionStatus(
                eq(72L),
                eq(List.of(ReconTask.Status.RUNNING)),
                eq(ReconTask.Status.CANCEL_REQUESTED),
                isNull(),
                isNull(),
                any());
    }

    private CreateReconTaskRequest browserRequest(String name) {
        CreateReconTaskRequest request = new CreateReconTaskRequest();
        request.setName(name);
        BrowserTargetConfig targetConfig = new BrowserTargetConfig();
        targetConfig.setChatUrl("https://example.com/chat");
        targetConfig.setTargetType("example");
        request.setTargetConfig(targetConfig);
        return request;
    }

    private void stubStatusTransitions(
            ReconTaskRepository taskRepository, ReconTask task) {
        when(taskRepository.transitionStatus(
                anyLong(), anyCollection(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<ReconTask.Status> expected =
                            (List<ReconTask.Status>) invocation.getArgument(1);
                    if (!expected.contains(task.getStatus())) {
                        return 0;
                    }
                    task.setStatus(invocation.getArgument(2));
                    task.setErrorMessage(invocation.getArgument(3));
                    task.setCompletedAt(invocation.getArgument(4));
                    return 1;
                });
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
                mock(ReplayProfileRepository.class),
                executor,
                new ObjectMapper());
    }
}
