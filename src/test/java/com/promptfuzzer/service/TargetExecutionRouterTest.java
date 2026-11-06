package com.promptfuzzer.service;

import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.dto.ReconConfig;
import com.promptfuzzer.entity.Task;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TargetExecutionRouterTest {

    @Test
    void browserOnlySkipsReplayAndProfileObservation() {
        BrowserService browserService = mock(BrowserService.class);
        ReplayProfileService replayProfileService =
                mock(ReplayProfileService.class);
        TargetExecutionRouter router =
                new TargetExecutionRouter(browserService, replayProfileService);
        when(browserService.sendMessageWithCapture(9L, "hello"))
                .thenReturn(new BrowserService.BrowserMessageResult(
                        "browser-response", List.of()));

        TargetExecutionRouter.TargetExecutionResult result = router.send(
                context(),
                state(),
                9L,
                "hello",
                ReconConfig.TargetExecutionMode.BROWSER_ONLY);

        assertEquals("BROWSER", result.getExecutionChannel());
        assertEquals("browser-response", result.getRawResponse());
        verifyNoInteractions(replayProfileService);
    }

    @Test
    void autoPrefersSuccessfulReplay() {
        BrowserService browserService = mock(BrowserService.class);
        ReplayProfileService replayProfileService =
                mock(ReplayProfileService.class);
        TargetExecutionRouter router =
                new TargetExecutionRouter(browserService, replayProfileService);
        ReplayProfileService.ReplayExecutionResult replay =
                new ReplayProfileService.ReplayExecutionResult(
                        null,
                        true,
                        200,
                        "application/json",
                        "{\"reply\":\"ok\"}",
                        "ok",
                        "POST",
                        "https://example.com/chat",
                        Map.of(),
                        "{}",
                        null,
                        null);
        when(replayProfileService.replayLatestCandidate(
                any(), any(), anyLong(), anyString()))
                .thenReturn(Optional.of(replay));

        TargetExecutionRouter.TargetExecutionResult result = router.send(
                context(),
                state(),
                9L,
                "hello",
                ReconConfig.TargetExecutionMode.AUTO);

        assertEquals("HTTP_REPLAY", result.getExecutionChannel());
        assertEquals("ok", result.getExtractedText());
        verify(browserService, never())
                .sendMessageWithCapture(anyLong(), anyString());
    }

    @Test
    void autoFallsBackToBrowserAfterReplayFailure() {
        BrowserService browserService = mock(BrowserService.class);
        ReplayProfileService replayProfileService =
                mock(ReplayProfileService.class);
        TargetExecutionRouter router =
                new TargetExecutionRouter(browserService, replayProfileService);
        ReplayProfileService.ReplayExecutionResult replay =
                new ReplayProfileService.ReplayExecutionResult(
                        null,
                        false,
                        500,
                        "application/json",
                        "",
                        "",
                        "POST",
                        "https://example.com/chat",
                        Map.of(),
                        "{}",
                        "HTTP_STATUS",
                        "status 500");
        when(replayProfileService.replayLatestCandidate(
                any(), any(), anyLong(), anyString()))
                .thenReturn(Optional.of(replay));
        when(browserService.sendMessageWithCapture(9L, "hello"))
                .thenReturn(new BrowserService.BrowserMessageResult(
                        "browser-response", List.of()));
        when(replayProfileService.observeBrowserTraffic(
                any(), any(), anyString(), any()))
                .thenReturn(Optional.empty());

        TargetExecutionRouter.TargetExecutionResult result = router.send(
                context(),
                state(),
                9L,
                "hello",
                ReconConfig.TargetExecutionMode.AUTO);

        assertEquals("BROWSER", result.getExecutionChannel());
        assertEquals("HTTP_STATUS", result.getFallbackType());
        assertEquals("status 500", result.getFallbackReason());
        verify(browserService).sendMessageWithCapture(9L, "hello");
    }

    private AgentWorkflowContext context() {
        Task task = new Task();
        task.setId(12L);
        return AgentWorkflowContext.builder()
                .task(task)
                .browserMode(true)
                .build();
    }

    private AgentWorkflowState state() {
        return AgentWorkflowState.builder()
                .taskId(12L)
                .targetChatIndex(0)
                .turnIndex(1)
                .build();
    }
}
