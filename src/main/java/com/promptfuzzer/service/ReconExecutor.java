package com.promptfuzzer.service;

import com.promptfuzzer.recon.workflow.ReconWorkflowEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReconExecutor {

    private final ReconWorkflowEngine workflowEngine;

    @Async("scanExecutor")
    public void execute(Long reconTaskId) {
        workflowEngine.execute(reconTaskId);
    }
}
