package com.promptfuzzer.controller;

import com.promptfuzzer.dto.CreateReconAttackUnitRequest;
import com.promptfuzzer.dto.ReconAttackUnitResponse;
import com.promptfuzzer.service.ReconAttackUnitService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ReconAttackUnitController {

    private final ReconAttackUnitService attackUnitService;

    @PostMapping("/api/recon-results/{resultId}/attack-units")
    public ResponseEntity<List<ReconAttackUnitResponse>> generateAttackUnits(
            @PathVariable Long resultId,
            @RequestBody CreateReconAttackUnitRequest request) {
        return ResponseEntity.ok(
                attackUnitService.generateAttackUnits(resultId, request));
    }

    @GetMapping("/api/recon-results/{resultId}/attack-units")
    public ResponseEntity<List<ReconAttackUnitResponse>> listAttackUnits(
            @PathVariable Long resultId) {
        return ResponseEntity.ok(
                attackUnitService.listAttackUnits(resultId));
    }

    @DeleteMapping("/api/attack-units/{attackUnitId}")
    public ResponseEntity<Void> deleteAttackUnit(
            @PathVariable Long attackUnitId) {
        attackUnitService.deleteAttackUnit(attackUnitId);
        return ResponseEntity.noContent().build();
    }
}
