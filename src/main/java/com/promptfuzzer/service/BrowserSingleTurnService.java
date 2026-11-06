package com.promptfuzzer.service;

import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
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
import java.util.Locale;

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
    private final TargetExecutionRouter targetExecutionRouter;
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
        int targetChatIndex = -1;
        AgentWorkflowContext context = AgentWorkflowContext.builder()
                .task(task)
                .browserMode(true)
                .build();

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
                        if (browserService.newChat(browserSessionId)) {
                            targetChatIndex++;
                        }
                    } catch (Exception ex) {
                        log.warn("NEW_CHAT failed for scout: {}", ex.getMessage());
                    }
                    if (targetChatIndex < 0) {
                        targetChatIndex = 0;
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

                    // Send through the same routing layer used by Agent so Scout can
                    // capture HTTP replay metadata and browser fallback evidence.
                    ScoutExecution execution;
                    try {
                        long startedAt = System.currentTimeMillis();
                        AgentWorkflowState state = AgentWorkflowState.builder()
                                .taskId(taskId)
                                .phase("SCOUT")
                                .turnIndex(totalSent)
                                .retryIndex(0)
                                .targetChatIndex(targetChatIndex)
                                .message(message)
                                .build();
                        TargetExecutionRouter.TargetExecutionResult executionResult =
                                targetExecutionRouter.send(context, state, browserSessionId, message);
                        execution = ScoutExecution.from(executionResult,
                                (int) (System.currentTimeMillis() - startedAt),
                                targetChatIndex);
                        if (execution.responseForJudge().isBlank()
                                || isDefaultGreeting(execution.responseForJudge())) {
                            log.warn("Scout payload {} empty response, skipping", payload.getId());
                            markPayloadResult(payload, task, ScanResult.Verdict.UNCERTAIN,
                                    null, "目标未返回有效新响应",
                                    null, execution);
                            uncertainCount++;
                            continue;
                        }
                    } catch (Exception e) {
                        log.error("Scout payload {} send failed: {}", payload.getId(), e.getMessage());
                        markPayloadResult(payload, task, ScanResult.Verdict.UNCERTAIN,
                                null, "浏览器发送异常: " + e.getMessage(),
                                null, ScoutExecution.error(message));
                        uncertainCount++;
                        continue;
                    }

                    // Judge
                    try {
                        JudgeService.JudgeResult judgeResult = judgeService.judge(
                                execution.responseForJudge(), goalId, attackContext);
                        ScanResult.Verdict verdict = ScanResult.Verdict.valueOf(judgeResult.getVerdict());
                        markPayloadResult(payload, task,
                                verdict,
                                judgeResult.getExtractedText(),
                                judgeResult.getEvidence(),
                                judgeResult.getRawResponse(),
                                execution);

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
                                null, "Judge异常: " + e.getMessage(),
                                null, execution);
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
                                   ScanResult.Verdict verdict, String extractedText, String evidence,
                                   String rawJudgeResponse, ScoutExecution execution) {
        payload.setStatus(ScanPayload.PayloadStatus.JUDGED);
        payloadRepo.save(payload);

        ScanResult result = new ScanResult();
        result.setTaskId(payload.getTaskId());
        result.setPayloadId(payload.getId());
        result.setVerdict(verdict);
        result.setHarmType(payload.getGoal());
        result.setExtractedText(extractedText);
        result.setEvidence(evidence);
        result.setRawJudgeResponse(rawJudgeResponse);
        if (execution != null) {
            result.setRequestBody(execution.requestBody());
            result.setResponseBody(execution.rawResponse());
            result.setResponseTime(execution.responseTimeMs());
            result.setExecutionChannel(execution.executionChannel());
            result.setReplayProfileId(execution.replayProfileId());
            result.setReplayProfileClassification(execution.replayProfileClassification());
            result.setStatusCode(execution.statusCode());
            result.setContentType(execution.contentType());
            result.setTargetChatIndex(execution.targetChatIndex());
        }
        resultRepo.save(result);
    }

    private boolean isDefaultGreeting(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.replaceAll("\\s+", "")
                .replaceAll("[。！？?!,.，、:：;；'\"“”‘’]", "")
                .toLowerCase(Locale.ROOT);
        return normalized.equals("今天我能为你做些什么")
                || normalized.equals("今天我能为您做些什么")
                || normalized.equals("我能为你做些什么")
                || normalized.equals("我能为您做些什么")
                || normalized.equals("有什么可以帮你")
                || normalized.equals("有什么可以帮您")
                || normalized.contains("howcanihelpyoutoday")
                || normalized.contains("whatcanidoforyoutoday");
    }

    private record ScoutExecution(
            String requestBody,
            String rawResponse,
            String extractedText,
            String executionChannel,
            Long replayProfileId,
            String replayProfileClassification,
            Integer statusCode,
            String contentType,
            Integer responseTimeMs,
            Integer targetChatIndex) {

        static ScoutExecution from(TargetExecutionRouter.TargetExecutionResult result,
                                   int responseTimeMs,
                                   int targetChatIndex) {
            if (result == null) {
                return error(null);
            }
            return new ScoutExecution(
                    result.getRequestBody(),
                    result.getRawResponse(),
                    result.getExtractedText(),
                    result.getExecutionChannel(),
                    result.getReplayProfileId(),
                    result.getReplayProfileClassification(),
                    result.getStatusCode(),
                    result.getContentType(),
                    Math.max(0, responseTimeMs),
                    targetChatIndex);
        }

        static ScoutExecution error(String requestBody) {
            return new ScoutExecution(requestBody, null, null, "ERROR",
                    null, null, null, null, 0, null);
        }

        String responseForJudge() {
            if (extractedText != null && !extractedText.isBlank()) {
                return extractedText;
            }
            return rawResponse != null ? rawResponse : "";
        }
    }
}
