package com.promptfuzzer.controller;

import com.promptfuzzer.dto.*;
import com.promptfuzzer.service.ReplayProfileService;
import com.promptfuzzer.service.ReconTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ReconTaskController {

    private final ReconTaskService reconTaskService;
    private final ReplayProfileService replayProfileService;

    @PostMapping("/api/recon-tasks")
    public ResponseEntity<ReconTaskResponse> createTask(
            @RequestBody CreateReconTaskRequest request) {
        return ResponseEntity.ok(reconTaskService.createTask(request));
    }

    @GetMapping("/api/recon-tasks")
    public ResponseEntity<List<ReconTaskResponse>> listTasks() {
        return ResponseEntity.ok(reconTaskService.listTasks());
    }

    @GetMapping("/api/recon-tasks/{taskId}")
    public ResponseEntity<ReconTaskResponse> getTask(@PathVariable Long taskId) {
        return ResponseEntity.ok(reconTaskService.getTask(taskId));
    }

    @PostMapping("/api/recon-tasks/{taskId}/cancel")
    public ResponseEntity<ReconTaskResponse> cancelTask(
            @PathVariable Long taskId) {
        return ResponseEntity.ok(reconTaskService.cancelTask(taskId));
    }

    @GetMapping("/api/recon-tasks/{taskId}/result")
    public ResponseEntity<ReconResultResponse> getResult(@PathVariable Long taskId) {
        return ResponseEntity.ok(reconTaskService.getResultByTaskId(taskId));
    }

    @GetMapping("/api/recon-tasks/{taskId}/traces")
    public ResponseEntity<List<ReconTraceResponse>> getTraces(@PathVariable Long taskId) {
        return ResponseEntity.ok(reconTaskService.getTraces(taskId));
    }

    @GetMapping("/api/recon-tasks/{taskId}/replay-profiles")
    public ResponseEntity<List<ReplayProfileResponse>> getReplayProfiles(@PathVariable Long taskId) {
        Long routingTaskId = -Math.abs(taskId);
        return ResponseEntity.ok(replayProfileService.listByTaskId(routingTaskId).stream()
                .map(ReplayProfileResponse::from)
                .toList());
    }

    @DeleteMapping("/api/recon-tasks/{taskId}")
    public ResponseEntity<Void> deleteTask(@PathVariable Long taskId) {
        reconTaskService.deleteTask(taskId);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/api/recon-results/{resultId}/application-surfaces")
    public ResponseEntity<List<ReconApplicationSurfaceResponse>> getApplicationSurfaces(
            @PathVariable Long resultId) {
        return ResponseEntity.ok(reconTaskService.getApplicationSurfaces(resultId));
    }

    @PostMapping("/api/recon-results/{resultId}/application-surfaces")
    public ResponseEntity<ReconApplicationSurfaceResponse> addApplicationSurface(
            @PathVariable Long resultId,
            @RequestBody UpdateReconApplicationSurfaceRequest request) {
        return ResponseEntity.ok(
                reconTaskService.addApplicationSurface(resultId, request));
    }

    @PatchMapping("/api/recon-results/{resultId}/application-surfaces/{surfaceId}")
    public ResponseEntity<ReconApplicationSurfaceResponse> updateApplicationSurface(
            @PathVariable Long resultId,
            @PathVariable Long surfaceId,
            @RequestBody UpdateReconApplicationSurfaceRequest request) {
        return ResponseEntity.ok(
                reconTaskService.updateApplicationSurface(resultId, surfaceId, request));
    }

    @DeleteMapping("/api/recon-results/{resultId}/application-surfaces/{surfaceId}")
    public ResponseEntity<Void> deleteApplicationSurface(
            @PathVariable Long resultId,
            @PathVariable Long surfaceId) {
        reconTaskService.deleteApplicationSurface(resultId, surfaceId);
        return ResponseEntity.noContent().build();
    }
}
