package com.promptfuzzer.controller;

import com.promptfuzzer.service.PayloadGeneratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AttackResourceController {

    private final PayloadGeneratorService payloadGeneratorService;

    @GetMapping("/goals")
    public Set<String> listGoals() {
        return payloadGeneratorService.getAvailableGoalIds();
    }

    @GetMapping("/techniques")
    public Set<String> listTechniques() {
        return payloadGeneratorService.getAvailableTechniqueIds();
    }
}
