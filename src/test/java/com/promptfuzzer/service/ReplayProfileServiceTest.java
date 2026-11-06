package com.promptfuzzer.service;

import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.ReplayProfile;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.repository.ReplayProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayProfileServiceTest {

    @Test
    void createsUnknownReplayProfileFromPromptBearingSseCapture() {
        ReplayProfileRepository repository = mock(ReplayProfileRepository.class);
        ReplayProfileService service = new ReplayProfileService(
                repository, mock(HttpRequestService.class), mock(BrowserService.class),
                mock(TargetResponseNormalizerService.class));
        when(repository.findFirstByTaskIdAndEndpointFingerprintOrderByUpdatedAtDesc(eq(12L), any()))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            ReplayProfile profile = invocation.getArgument(0);
            profile.setId(99L);
            return profile;
        });

        BrowserService.TrafficExchange exchange = new BrowserService.TrafficExchange(
                "POST",
                "https://veda-claw.tiktok-row.net/api/v1/chat/stream?trace=abc",
                "fetch",
                "content-type: application/json\nauthorization: Bearer live-token\n",
                "content-type: application/json\nauthorization: [REDACTED]\n",
                "{\"message\":\"hello-control\"}",
                "{\"message\":\"hello-control\"}",
                27,
                true,
                200,
                "content-type: text/event-stream\n",
                "data: {\"answer\":\"ok\"}",
                21,
                "text/event-stream",
                1L,
                2L,
                12);

        Optional<ReplayProfile> saved = service.observeBrowserTraffic(
                context(), state(), "hello-control", List.of(exchange));

        assertTrue(saved.isPresent());
        ReplayProfile profile = saved.get();
        assertEquals(99L, profile.getId());
        assertEquals(ReplayProfile.Classification.UNKNOWN, profile.getClassification());
        assertEquals("POST", profile.getMethod());
        assertEquals("https://veda-claw.tiktok-row.net/api/v1/chat/stream?trace=abc", profile.getUrl());
        assertEquals("$.message", profile.getPayloadFieldPath());
        assertTrue(profile.getRequestTemplate().contains("{{PAYLOAD}}"));
        assertTrue(profile.getDynamicBindings().contains("authorization"));
        assertTrue(profile.getResponseExtractionRule().contains("SSE"));
    }

    @Test
    void ignoresTrafficWithoutSubmittedPrompt() {
        ReplayProfileRepository repository = mock(ReplayProfileRepository.class);
        ReplayProfileService service = new ReplayProfileService(
                repository, mock(HttpRequestService.class), mock(BrowserService.class),
                mock(TargetResponseNormalizerService.class));
        BrowserService.TrafficExchange exchange = new BrowserService.TrafficExchange(
                "POST",
                "https://example.test/metrics",
                "fetch",
                "",
                "",
                "{\"event\":\"click\"}",
                "{\"event\":\"click\"}",
                17,
                false,
                200,
                "content-type: application/json\n",
                "{}",
                2,
                "application/json",
                1L,
                2L,
                5);

        Optional<ReplayProfile> saved = service.observeBrowserTraffic(
                context(), state(), "hello-control", List.of(exchange));

        assertTrue(saved.isEmpty());
        verify(repository, never()).save(any());
    }

    @Test
    void buildsReplayRequestWithBrowserCookieAndExtractsSseText() throws Exception {
        ReplayProfileRepository repository = mock(ReplayProfileRepository.class);
        BrowserService browserService = mock(BrowserService.class);
        ReplayProfileService service = new ReplayProfileService(
                repository, mock(HttpRequestService.class), browserService,
                mock(TargetResponseNormalizerService.class));
        ReplayProfile profile = new ReplayProfile();
        profile.setMethod("POST");
        profile.setUrl("https://veda-claw.tiktok-row.net/api/v1/chat/stream");
        profile.setRequestTemplate("""
                {"method":"POST","url":"https://veda-claw.tiktok-row.net/api/v1/chat/stream","headersPreview":"content-type: application/json\\nauthorization: [REDACTED]\\n","bodyTemplate":"{\\"message\\":\\"{{PAYLOAD}}\\"}"}
                """);
        when(browserService.cookieHeaderForUrl(77L, "https://veda-claw.tiktok-row.net/api/v1/chat/stream"))
                .thenReturn("session=abc");
        when(browserService.dynamicReplayHeadersForUrl(77L, "https://veda-claw.tiktok-row.net/api/v1/chat/stream"))
                .thenReturn(Map.of(
                        "authorization", "Bearer live-token",
                        "cookie", "stale=1",
                        "content-length", "999"));

        ReplayProfileService.ReplayRequest request =
                service.buildReplayRequest(profile, null, 77L, "hello \"quoted\"");

        assertEquals("POST", request.method());
        assertEquals("session=abc", request.headers().get("Cookie"));
        assertEquals(null, request.headers().get("Authorization"));
        assertEquals("Bearer live-token", request.headers().get("authorization"));
        assertTrue(request.body().contains("hello \\\"quoted\\\""));

    }

    @Test
    void removesStaleDynamicHeadersCaseInsensitively() throws Exception {
        ReplayProfileRepository repository = mock(ReplayProfileRepository.class);
        BrowserService browserService = mock(BrowserService.class);
        ReplayProfileService service = new ReplayProfileService(
                repository, mock(HttpRequestService.class), browserService,
                mock(TargetResponseNormalizerService.class));
        ReplayProfile profile = new ReplayProfile();
        profile.setMethod("POST");
        profile.setUrl("https://veda-claw.tiktok-row.net/api/v1/chat/stream");
        profile.setRequestTemplate("""
                {"method":"POST","url":"https://veda-claw.tiktok-row.net/api/v1/chat/stream","headers":"content-type: application/json\\nauthorization: Bearer stale\\ncookie: stale=1\\ncontent-length: 999\\nhost: stale.example\\n","bodyTemplate":"{\\"message\\":\\"{{PAYLOAD}}\\"}"}
                """);
        when(browserService.cookieHeaderForUrl(77L, "https://veda-claw.tiktok-row.net/api/v1/chat/stream"))
                .thenReturn("session=abc");
        when(browserService.dynamicReplayHeadersForUrl(77L, "https://veda-claw.tiktok-row.net/api/v1/chat/stream"))
                .thenReturn(Map.of(
                        "authorization", "Bearer live-token",
                        "cookie", "stale=2",
                        "content-length", "999"));

        ReplayProfileService.ReplayRequest request =
                service.buildReplayRequest(profile, null, 77L, "hello");

        assertEquals(null, request.headers().get("host"));
        assertEquals(null, request.headers().get("content-length"));
        assertEquals("session=abc", request.headers().get("Cookie"));
        assertEquals(null, request.headers().get("cookie"));
        assertEquals(null, request.headers().get("content-length"));
        assertEquals("Bearer live-token", request.headers().get("authorization"));
    }

    @Test
    void storesFullReplayHeadersAndBodyTemplate() {
        ReplayProfileRepository repository = mock(ReplayProfileRepository.class);
        ReplayProfileService service = new ReplayProfileService(
                repository, mock(HttpRequestService.class), mock(BrowserService.class),
                mock(TargetResponseNormalizerService.class));
        when(repository.findFirstByTaskIdAndEndpointFingerprintOrderByUpdatedAtDesc(eq(12L), any()))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String longHeaderValue = "x".repeat(5000);
        String longBodySuffix = "y".repeat(5000);
        BrowserService.TrafficExchange exchange = new BrowserService.TrafficExchange(
                "POST",
                "https://veda-claw.tiktok-row.net/api/v1/chat/stream",
                "fetch",
                "content-type: application/json\nx-long-token: " + longHeaderValue + "\n",
                "content-type: application/json\nx-long-token: [REDACTED]\n",
                "{\"message\":\"hello-control\",\"tail\":\"" + longBodySuffix + "\"}",
                "{\"message\":\"hello-control\",\"tail\":\"" + longBodySuffix + "\"}",
                5037,
                true,
                200,
                "content-type: text/event-stream\n",
                "data: {\"answer\":\"ok\"}",
                21,
                "text/event-stream",
                1L,
                2L,
                12);

        Optional<ReplayProfile> saved = service.observeBrowserTraffic(
                context(), state(), "hello-control", List.of(exchange));

        assertTrue(saved.isPresent());
        String requestTemplate = saved.get().getRequestTemplate();
        assertTrue(requestTemplate.contains(longHeaderValue));
        assertTrue(requestTemplate.contains(longBodySuffix));
        assertTrue(requestTemplate.contains("{{PAYLOAD}}"));
    }

    @Test
    void explicitTargetChatIndexDoesNotFallbackToOtherReplayProfiles() {
        ReplayProfileRepository repository = mock(ReplayProfileRepository.class);
        ReplayProfileService service = new ReplayProfileService(
                repository, mock(HttpRequestService.class), mock(BrowserService.class),
                mock(TargetResponseNormalizerService.class));
        Task task = new Task();
        task.setId(12L);
        AgentWorkflowState state = AgentWorkflowState.builder()
                .targetChatIndex(2)
                .build();
        when(repository.findByTaskIdAndTargetChatIndexOrderByUpdatedAtDesc(12L, 2))
                .thenReturn(List.of());

        Optional<ReplayProfile> candidate = service.findReplayCandidate(task, state);

        assertTrue(candidate.isEmpty());
        verify(repository, never()).findByTaskIdOrderByUpdatedAtDesc(12L);
    }

    private AgentWorkflowContext context() {
        Task task = new Task();
        task.setId(12L);
        task.setTargetConfig("{\"targetType\":\"veda-claw\"}");
        AgentSession session = new AgentSession();
        session.setId(34L);
        session.setSessionIndex(1);
        return AgentWorkflowContext.builder()
                .task(task)
                .session(session)
                .browserMode(true)
                .build();
    }

    private AgentWorkflowState state() {
        return AgentWorkflowState.builder()
                .turnIndex(3)
                .targetChatIndex(2)
                .build();
    }
}
