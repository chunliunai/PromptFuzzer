package com.promptfuzzer.service;

import com.promptfuzzer.entity.ScanPayload;
import com.promptfuzzer.entity.ScanResult;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.repository.ScanPayloadRepository;
import com.promptfuzzer.repository.ScanResultRepository;
import com.promptfuzzer.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScanExecutorService {

    private final TaskRepository taskRepository;
    private final ScanPayloadRepository scanPayloadRepository;
    private final ScanResultRepository scanResultRepository;
    private final HttpRequestService httpRequestService;
    private final JudgeService judgeService;
    private final OobService oobService;

    @Async("scanExecutor")
    public void executeTask(Long taskId) {
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return;

        task.setStatus(Task.TaskStatus.RUNNING);
        taskRepository.save(task);
        log.info("Task {} started", taskId);

        try {
            List<ScanPayload> payloads = scanPayloadRepository.findByTaskIdAndStatus(
                    taskId, ScanPayload.PayloadStatus.PENDING);

            for (ScanPayload payload : payloads) {
                processSinglePayload(task, payload);
            }

            task.setStatus(Task.TaskStatus.COMPLETED);
            log.info("Task {} completed. success={} fail={} uncertain={}",
                    taskId, task.getSuccessCount(), task.getFailCount(), task.getUncertainCount());

        } catch (Exception e) {
            log.error("Task {} failed", taskId, e);
            task.setStatus(Task.TaskStatus.FAILED);
        }

        taskRepository.save(task);
        oobService.finalizeTaskOobResult(taskId);
    }

    private void processSinglePayload(Task task, ScanPayload payload) {
        long startTime = System.currentTimeMillis();
        ScanResult result = new ScanResult();
        result.setPayloadId(payload.getId());
        result.setTaskId(task.getId());

        try {
            String rawRequest = buildRequest(task.getRawRequestTemplate(), payload.getContent());
            result.setRequestBody(rawRequest);

            String rawResponse = httpRequestService.send(task.getRawRequestTemplate(), payload.getContent());
            result.setResponseBody(rawResponse);
            result.setResponseTime((int) (System.currentTimeMillis() - startTime));

            payload.setStatus(ScanPayload.PayloadStatus.SENT);
            scanPayloadRepository.save(payload);

            if (oobService.isOobGoal(payload.getGoal())) {
                result.setVerdict(ScanResult.Verdict.UNCERTAIN);
                result.setEvidence("SSRF/OOB goal uses task-level DNSLog verification; see Task.reportSummary.");
                result.setExtractedText("");
                result.setHarmType(payload.getGoal());
                updateTaskCount(task, "UNCERTAIN");
            } else {
                JudgeService.JudgeResult judgeResult = judgeService.judge(rawResponse, payload.getGoal(), task.getAttackContext());
                result.setVerdict(ScanResult.Verdict.valueOf(judgeResult.getVerdict()));
                result.setEvidence(judgeResult.getEvidence());
                result.setExtractedText(judgeResult.getExtractedText());
                result.setHarmType(payload.getGoal());
                result.setRawJudgeResponse(judgeResult.getRawResponse());

                updateTaskCount(task, judgeResult.getVerdict());
            }

        } catch (Exception e) {
            log.error("Payload {} execution error", payload.getId(), e);
            result.setVerdict(ScanResult.Verdict.UNCERTAIN);
            result.setEvidence("执行异常: " + e.getMessage());
            task.setFailCount(task.getFailCount() + 1);
        }

        scanResultRepository.save(result);
        payload.setStatus(ScanPayload.PayloadStatus.JUDGED);
        scanPayloadRepository.save(payload);
        taskRepository.save(task);
    }

    private String buildRequest(String template, String payload) {
        if (template == null) return payload;
        return template.replace("{{PAYLOAD}}", payload);
    }

    private void updateTaskCount(Task task, String verdict) {
        switch (verdict) {
            case "SUCCESS":
                task.setSuccessCount(task.getSuccessCount() + 1);
                break;
            case "FAIL":
                task.setFailCount(task.getFailCount() + 1);
                break;
            default:
                task.setUncertainCount(task.getUncertainCount() + 1);
        }
    }
}
