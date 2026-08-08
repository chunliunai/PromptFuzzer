package com.promptfuzzer.controller;

import com.promptfuzzer.dto.CreateReconAttackCandidateRequest;
import com.promptfuzzer.dto.ReconAttackCandidateResponse;
import com.promptfuzzer.dto.TaskResponse;
import com.promptfuzzer.dto.UpdateReconAttackCandidateRequest;
import com.promptfuzzer.service.ReconAttackCandidateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ReconAttackCandidateController {

    private final ReconAttackCandidateService candidateService;

    @PostMapping("/api/recon-results/{resultId}/attack-candidates")
    public ResponseEntity<List<ReconAttackCandidateResponse>> generateCandidates(
            @PathVariable Long resultId,
            @RequestBody CreateReconAttackCandidateRequest request) {
        return ResponseEntity.ok(
                candidateService.generateCandidates(resultId, request));
    }

    @GetMapping("/api/recon-results/{resultId}/attack-candidates")
    public ResponseEntity<List<ReconAttackCandidateResponse>> listCandidates(
            @PathVariable Long resultId) {
        return ResponseEntity.ok(candidateService.listCandidates(resultId));
    }

    @PatchMapping("/api/attack-candidates/{candidateId}")
    public ResponseEntity<ReconAttackCandidateResponse> updateCandidate(
            @PathVariable Long candidateId,
            @RequestBody UpdateReconAttackCandidateRequest request) {
        return ResponseEntity.ok(
                candidateService.updateCandidate(candidateId, request));
    }

    @DeleteMapping("/api/attack-candidates/{candidateId}")
    public ResponseEntity<Void> deleteCandidate(
            @PathVariable Long candidateId) {
        candidateService.deleteCandidate(candidateId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/attack-candidates/{candidateId}/execute")
    public ResponseEntity<TaskResponse> executeCandidate(
            @PathVariable Long candidateId) {
        return ResponseEntity.ok(candidateService.executeCandidate(candidateId));
    }
}
