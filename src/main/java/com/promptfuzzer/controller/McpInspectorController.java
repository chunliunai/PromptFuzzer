package com.promptfuzzer.controller;

import com.promptfuzzer.dto.McpInspectorSessionResponse;
import com.promptfuzzer.dto.McpInspectorStartRequest;
import com.promptfuzzer.service.McpInspectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP Inspector 实验台接口（CVE-2026-119025 复现用，纯新增）。
 */
@RestController
@RequestMapping("/api/mcp-inspector")
@RequiredArgsConstructor
public class McpInspectorController {

    private final McpInspectorService inspectorService;

    @GetMapping("/status")
    public ResponseEntity<McpInspectorSessionResponse> status() {
        return ResponseEntity.ok(inspectorService.status());
    }

    @PostMapping("/start")
    public ResponseEntity<McpInspectorSessionResponse> start(
            @RequestBody McpInspectorStartRequest request) {
        return ResponseEntity.ok(inspectorService.start(request));
    }

    @PostMapping("/stop")
    public ResponseEntity<McpInspectorSessionResponse> stop() {
        return ResponseEntity.ok(inspectorService.stop());
    }
}
