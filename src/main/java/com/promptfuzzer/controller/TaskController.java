package com.promptfuzzer.controller;

import com.promptfuzzer.dto.CreateTaskRequest;
import com.promptfuzzer.dto.PageResponse;
import com.promptfuzzer.dto.ReplayProfileResponse;
import com.promptfuzzer.dto.ReplayProfileValidationRequest;
import com.promptfuzzer.dto.ReplayProfileValidationResponse;
import com.promptfuzzer.dto.ScanResultResponse;
import com.promptfuzzer.dto.TaskResponse;
import com.promptfuzzer.service.ReplayProfileService;
import com.promptfuzzer.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final ReplayProfileService replayProfileService;

    @PostMapping
    public ResponseEntity<TaskResponse> createTask(@RequestBody CreateTaskRequest request) {
        return ResponseEntity.ok(taskService.createTask(request));
    }

    @GetMapping
    public ResponseEntity<List<TaskResponse>> listTasks() {
        return ResponseEntity.ok(taskService.listTasks());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.getTask(id));
    }

    @GetMapping("/{id}/results")
    public ResponseEntity<PageResponse<ScanResultResponse>> getResults(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(taskService.getTaskResults(id, page, size));
    }

    @GetMapping("/{id}/replay-profiles")
    public ResponseEntity<List<ReplayProfileResponse>> getReplayProfiles(@PathVariable Long id) {
        return ResponseEntity.ok(replayProfileService.listByTaskId(id).stream()
                .map(ReplayProfileResponse::from)
                .toList());
    }

    @PostMapping("/{id}/replay-profiles/{profileId}/validate")
    public ResponseEntity<ReplayProfileValidationResponse> validateReplayProfile(
            @PathVariable Long id,
            @PathVariable Long profileId,
            @RequestBody ReplayProfileValidationRequest request) {
        return ResponseEntity.ok(replayProfileService.validateProfile(
                id, profileId, request.getBrowserSessionId(), request.getPrompt()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable Long id) {
        taskService.deleteTask(id);
        return ResponseEntity.noContent().build();
    }
}
