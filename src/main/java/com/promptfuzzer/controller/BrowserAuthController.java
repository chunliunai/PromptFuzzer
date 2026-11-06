package com.promptfuzzer.controller;

import com.promptfuzzer.dto.BrowserTargetProfileRequest;
import com.promptfuzzer.dto.BrowserTargetProfileResponse;
import com.promptfuzzer.service.BrowserProfileSmokeService;
import com.promptfuzzer.service.BrowserService;
import com.promptfuzzer.service.BrowserTargetProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/browser")
@RequiredArgsConstructor
public class BrowserAuthController {

    private final BrowserService browserService;
    private final BrowserTargetProfileService profileService;
    private final BrowserProfileSmokeService smokeService;

    @GetMapping("/targets")
    public ResponseEntity<List<BrowserTargetProfileResponse>> listTargets() {
        return ResponseEntity.ok(profileService.listProfiles().stream()
                .map(BrowserTargetProfileResponse::from)
                .toList());
    }

    @GetMapping("/targets/{targetType}")
    public ResponseEntity<BrowserTargetProfileResponse> getTarget(
            @PathVariable String targetType) {
        return ResponseEntity.ok(profileService.findByTargetTypeWithDefaults(targetType)
                .map(BrowserTargetProfileResponse::from)
                .orElseGet(() -> BrowserTargetProfileResponse.missing(targetType)));
    }

    @PostMapping("/targets")
    public ResponseEntity<BrowserTargetProfileResponse> saveTarget(
            @RequestBody BrowserTargetProfileRequest request) {
        var config = request.getTargetConfig();
        if (config != null && (config.getTargetType() == null || config.getTargetType().isBlank())) {
            config.setTargetType(request.getTargetType());
        }
        return ResponseEntity.ok(BrowserTargetProfileResponse.from(
                profileService.upsertFromConfig(config, request.getDisplayName())));
    }

    @PostMapping("/targets/login/start")
    public ResponseEntity<BrowserTargetProfileResponse> startLogin(
            @RequestBody BrowserTargetProfileRequest request) {
        var config = request.getTargetConfig();
        if (config != null && (config.getTargetType() == null || config.getTargetType().isBlank())) {
            config.setTargetType(request.getTargetType());
        }
        return ResponseEntity.ok(BrowserTargetProfileResponse.from(
                browserService.startLogin(config)));
    }

    @PostMapping("/targets/{targetType}/auth-ready")
    public ResponseEntity<BrowserTargetProfileResponse> targetAuthReady(
            @PathVariable String targetType) {
        return ResponseEntity.ok(BrowserTargetProfileResponse.from(
                browserService.signalAuthReady(targetType)));
    }

    @PostMapping("/targets/{targetType}/clear-auth")
    public ResponseEntity<BrowserTargetProfileResponse> clearAuth(
            @PathVariable String targetType) {
        return ResponseEntity.ok(BrowserTargetProfileResponse.from(
                browserService.clearAuthState(targetType)));
    }

    @PostMapping("/targets/{targetType}/smoke")
    public ResponseEntity<BrowserProfileSmokeService.SmokeResult> smokeTarget(
            @PathVariable String targetType,
            @RequestBody(required = false) Map<String, String> body) {
        String prompt = body != null ? body.get("prompt") : null;
        return ResponseEntity.ok(smokeService.run(targetType, prompt));
    }

    /**
     * Signal that manual login is complete. Call this after logging into the target site.
     */
    @PostMapping("/auth-ready")
    public ResponseEntity<?> authReady(@RequestBody(required = false) Map<String, String> body) {
        String targetType = body != null ? body.get("targetType") : null;
        if (targetType == null || targetType.isBlank()) {
            browserService.signalAuthReady();
            return ResponseEntity.ok("Auth signal sent. Agent will proceed.");
        }
        return ResponseEntity.ok(BrowserTargetProfileResponse.from(
                browserService.signalAuthReady(targetType)));
    }
}
