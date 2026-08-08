package com.promptfuzzer.controller;

import com.promptfuzzer.service.BrowserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/browser")
@RequiredArgsConstructor
public class BrowserAuthController {

    private final BrowserService browserService;

    /**
     * Signal that manual login is complete. Call this after logging into the target site.
     */
    @PostMapping("/auth-ready")
    public ResponseEntity<String> authReady() {
        browserService.signalAuthReady();
        return ResponseEntity.ok("Auth signal sent. Agent will proceed.");
    }
}
