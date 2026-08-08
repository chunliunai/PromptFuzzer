package com.promptfuzzer.controller;

import com.promptfuzzer.dto.CreateTaskRequest;
import com.promptfuzzer.dto.PageResponse;
import com.promptfuzzer.dto.ScanResultResponse;
import com.promptfuzzer.dto.TaskResponse;
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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable Long id) {
        taskService.deleteTask(id);
        return ResponseEntity.noContent().build();
    }
}
