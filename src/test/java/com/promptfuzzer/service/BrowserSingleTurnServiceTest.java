package com.promptfuzzer.service;

import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.config.BrowserTargetConfig;
import com.promptfuzzer.entity.ScanPayload;
import com.promptfuzzer.entity.ScanResult;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.repository.ScanPayloadRepository;
import com.promptfuzzer.repository.ScanResultRepository;
import com.promptfuzzer.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrowserSingleTurnServiceTest {

    @Test
    void scoutPersistsExecutionAuditFields() throws Exception {
        PayloadGeneratorService payloadGenerator = mock(PayloadGeneratorService.class);
        BrowserService browserService = mock(BrowserService.class);
        TargetExecutionRouter targetExecutionRouter = mock(TargetExecutionRouter.class);
        JudgeService judgeService = mock(JudgeService.class);
        ScanPayloadRepository payloadRepo = mock(ScanPayloadRepository.class);
        ScanResultRepository resultRepo = mock(ScanResultRepository.class);
        TaskRepository taskRepo = mock(TaskRepository.class);
        BrowserSingleTurnService service = new BrowserSingleTurnService(
                payloadGenerator, browserService, targetExecutionRouter, judgeService,
                payloadRepo, resultRepo, taskRepo);

        Task task = new Task();
        task.setId(38L);
        when(payloadGenerator.generate("authorization_bypass", "role_play", "ctx", 1))
                .thenReturn(List.of("payload"));
        when(browserService.newChat(7L)).thenReturn(true);
        when(payloadRepo.save(any(ScanPayload.class))).thenAnswer(invocation -> {
            ScanPayload payload = invocation.getArgument(0);
            if (payload.getId() == null) {
                payload.setId(49L);
            }
            return payload;
        });
        when(taskRepo.findById(38L)).thenReturn(Optional.of(task));

        TargetExecutionRouter.TargetExecutionResult execution =
                new TargetExecutionRouter.TargetExecutionResult();
        execution.setExecutionChannel("HTTP_REPLAY");
        execution.setRequestBody("POST https://target/api\n\n{\"message\":\"payload\"}");
        execution.setRawResponse("HTTP 200\ndata: {\"content\":\"fresh response\"}");
        execution.setExtractedText("fresh response");
        execution.setReplayProfileId(26L);
        execution.setReplayProfileClassification("HTTP_REPLAYABLE");
        execution.setStatusCode(200);
        execution.setContentType("text/event-stream");
        when(targetExecutionRouter.send(any(), any(), eq(7L), eq("payload"))).thenAnswer(invocation -> {
            Thread.sleep(5);
            return execution;
        });

        JudgeService.JudgeResult judge = new JudgeService.JudgeResult();
        judge.setVerdict("FAIL");
        judge.setEvidence("type=NONE");
        judge.setExtractedText("fresh response");
        judge.setRawResponse("{\"verdict\":\"FAIL\"}");
        when(judgeService.judge("fresh response", "authorization_bypass", "ctx")).thenReturn(judge);

        boolean hasSuccess = service.runScoutPhase(38L, task, 7L, new BrowserTargetConfig(),
                "ctx", List.of("authorization_bypass"), List.of("role_play"));

        assertThat(hasSuccess).isFalse();
        ArgumentCaptor<AgentWorkflowState> stateCaptor = ArgumentCaptor.forClass(AgentWorkflowState.class);
        verify(targetExecutionRouter).send(any(), stateCaptor.capture(), eq(7L), eq("payload"));
        assertThat(stateCaptor.getValue().getTargetChatIndex()).isEqualTo(0);
        ArgumentCaptor<ScanResult> resultCaptor = ArgumentCaptor.forClass(ScanResult.class);
        verify(resultRepo).save(resultCaptor.capture());
        ScanResult saved = resultCaptor.getValue();
        assertThat(saved.getResponseTime()).isGreaterThan(0);
        assertThat(saved.getRequestBody()).contains("\"message\":\"payload\"");
        assertThat(saved.getResponseBody()).contains("fresh response");
        assertThat(saved.getExecutionChannel()).isEqualTo("HTTP_REPLAY");
        assertThat(saved.getReplayProfileId()).isEqualTo(26L);
        assertThat(saved.getStatusCode()).isEqualTo(200);
        assertThat(saved.getTargetChatIndex()).isEqualTo(0);
        assertThat(saved.getRawJudgeResponse()).contains("FAIL");
    }

    @Test
    void scoutDropsDefaultGreetingBeforeJudge() {
        PayloadGeneratorService payloadGenerator = mock(PayloadGeneratorService.class);
        BrowserService browserService = mock(BrowserService.class);
        TargetExecutionRouter targetExecutionRouter = mock(TargetExecutionRouter.class);
        JudgeService judgeService = mock(JudgeService.class);
        ScanPayloadRepository payloadRepo = mock(ScanPayloadRepository.class);
        ScanResultRepository resultRepo = mock(ScanResultRepository.class);
        TaskRepository taskRepo = mock(TaskRepository.class);
        BrowserSingleTurnService service = new BrowserSingleTurnService(
                payloadGenerator, browserService, targetExecutionRouter, judgeService,
                payloadRepo, resultRepo, taskRepo);

        Task task = new Task();
        task.setId(38L);
        when(payloadGenerator.generate("authorization_bypass", "role_play", "ctx", 1))
                .thenReturn(List.of("payload"));
        when(browserService.newChat(7L)).thenReturn(true);
        when(payloadRepo.save(any(ScanPayload.class))).thenAnswer(invocation -> {
            ScanPayload payload = invocation.getArgument(0);
            if (payload.getId() == null) {
                payload.setId(49L);
            }
            return payload;
        });
        when(taskRepo.findById(38L)).thenReturn(Optional.of(task));

        TargetExecutionRouter.TargetExecutionResult execution =
                new TargetExecutionRouter.TargetExecutionResult();
        execution.setExecutionChannel("BROWSER");
        execution.setRequestBody("payload");
        execution.setRawResponse("今天我能为你做些什么？");
        when(targetExecutionRouter.send(any(), any(), eq(7L), eq("payload"))).thenReturn(execution);

        service.runScoutPhase(38L, task, 7L, new BrowserTargetConfig(),
                "ctx", List.of("authorization_bypass"), List.of("role_play"));

        verify(judgeService, never()).judge(anyString(), anyString(), anyString());
        ArgumentCaptor<ScanResult> captor = ArgumentCaptor.forClass(ScanResult.class);
        verify(resultRepo).save(captor.capture());
        ScanResult saved = captor.getValue();
        assertThat(saved.getVerdict()).isEqualTo(ScanResult.Verdict.UNCERTAIN);
        assertThat(saved.getEvidence()).isEqualTo("目标未返回有效新响应");
        assertThat(saved.getResponseBody()).isEqualTo("今天我能为你做些什么？");
        assertThat(saved.getExecutionChannel()).isEqualTo("BROWSER");
    }
}
