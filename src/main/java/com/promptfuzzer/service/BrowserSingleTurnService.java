package com.promptfuzzer.service;

import com.promptfuzzer.config.BrowserTargetConfig;
import com.promptfuzzer.entity.ScanPayload;
import com.promptfuzzer.entity.ScanResult;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.repository.ScanPayloadRepository;
import com.promptfuzzer.repository.ScanResultRepository;
import com.promptfuzzer.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Browser-mode single-turn executor for AUTO Phase 1.
 * Opens one browser session, sends payloads one by one with NEW_CHAT between each,
 * judges responses, persists results.
 * Returns true if any payload succeeds.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserSingleTurnService {

    private final PayloadGeneratorService payloadGenerator;
    private final BrowserService browserService;
    private final JudgeService judgeService;
    private final ScanPayloadRepository payloadRepo;
    private final ScanResultRepository resultRepo;
    private final TaskRepository taskRepo;

    /**
     * Run single-turn scout phase in browser mode.
     * @return true if any payload succeeded, false if all failed.
     */
    public boolean runScoutPhase(Long taskId, Task task, Long browserSessionId,
                                  BrowserTargetConfig config, String attackContext,
                                  List<String> goalIds, List<String> techniqueIds) {
        log.info("BrowserSingleTurn Scout starting for task {}: goals={} techniques={}",
                taskId, goalIds, techniqueIds);

        int totalSent = 0;
        int successCount = 0;
        int failCount = 0;
        int uncertainCount = 0;

        for (String goalId : goalIds) {
            for (String techniqueId : techniqueIds) {
                // Generate 1 payload per combination
                List<String> messages = payloadGenerator.generate(
                        goalId, techniqueId, attackContext, 1);
                if (messages.isEmpty()) {
                    log.warn("No payload generated for goal={} technique={}", goalId, techniqueId);
                    continue;
                }

                for (int i = 0; i < messages.size(); i++) {
                    String message = messages.get(i);

                    // NEW_CHAT at START: ensure clean slate regardless of previous outcome
                    try {
                        browserService.newChat(browserSessionId);
                    } catch (Exception ex) {
                        log.warn("NEW_CHAT failed for scout: {}", ex.getMessage());
                    }

                    // Persist payload
                    ScanPayload payload = new ScanPayload();
                    payload.setTaskId(taskId);
                    payload.setStrategyId("auto_scout");
                    payload.setGoal(goalId);
                    payload.setTechnique(techniqueId);
                    payload.setTemplateName(goalId + "_" + techniqueId + "_" + (i + 1));
                    payload.setContent(message);
                    payload.setStatus(ScanPayload.PayloadStatus.PENDING);
                    payloadRepo.save(payload);
                    totalSent++;

                    // Send via browser
                    String rawResponse;
                    try {
                        rawResponse = browserService.sendMessage(browserSessionId, message);
                        if (rawResponse == null || rawResponse.isBlank()) {
                            log.warn("Scout payload {} empty response, skipping", payload.getId());
                            markPayloadResult(payload, task, ScanResult.Verdict.UNCERTAIN,
                                    null, "浏览器返回空响应");
                            uncertainCount++;
                            continue;
                        }
                    } catch (Exception e) {
                        log.error("Scout payload {} send failed: {}", payload.getId(), e.getMessage());
                        markPayloadResult(payload, task, ScanResult.Verdict.UNCERTAIN,
                                null, "浏览器发送异常: " + e.getMessage());
                        uncertainCount++;
                        continue;
                    }

                    // Judge
                    try {
                        JudgeService.JudgeResult judgeResult = judgeService.judge(
                                rawResponse, goalId, attackContext);
                        ScanResult.Verdict verdict = ScanResult.Verdict.valueOf(judgeResult.getVerdict());
                        markPayloadResult(payload, task,
                                verdict,
                                judgeResult.getExtractedText(),
                                judgeResult.getEvidence());

                        log.info("Scout: goal={} tech={} verdict={}",
                                goalId, techniqueId, judgeResult.getVerdict());

                        switch (verdict) {
                            case SUCCESS:
                                successCount++;
                                // Record the last success, but don't stop — finish remaining for intelligence
                                break;
                            case FAIL:
                                failCount++;
                                break;
                            default:
                                uncertainCount++;
                                break;
                        }
                    } catch (Exception e) {
                        log.error("Scout payload {} judge failed: {}", payload.getId(), e.getMessage());
                        markPayloadResult(payload, task, ScanResult.Verdict.UNCERTAIN,
                                null, "Judge异常: " + e.getMessage());
                        uncertainCount++;
                    }

                }
            }
        }

        // Update task counts
        task = taskRepo.findById(taskId).orElse(task);
        task.setTotalCount(task.getTotalCount() + totalSent);
        task.setSuccessCount(task.getSuccessCount() + successCount);
        task.setFailCount(task.getFailCount() + failCount);
        task.setUncertainCount(task.getUncertainCount() + uncertainCount);
        taskRepo.save(task);

        boolean hasSuccess = successCount > 0;
        log.info("BrowserSingleTurn Scout done: {} sent, {} success → {}",
                totalSent, successCount, hasSuccess ? "COMPLETED" : "PROCEED TO PHASE 2");
        return hasSuccess;
    }

    private void markPayloadResult(ScanPayload payload, Task task,
                                    ScanResult.Verdict verdict, String extractedText, String evidence) {
        payload.setStatus(ScanPayload.PayloadStatus.SENT);
        payloadRepo.save(payload);

        ScanResult result = new ScanResult();
        result.setTaskId(payload.getTaskId());
        result.setPayloadId(payload.getId());
        result.setVerdict(verdict);
        result.setHarmType(payload.getGoal());
        result.setExtractedText(extractedText);
        result.setEvidence(evidence);
        result.setResponseTime(0);
        resultRepo.save(result);
    }
}
