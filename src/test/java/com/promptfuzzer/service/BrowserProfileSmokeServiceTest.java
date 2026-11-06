package com.promptfuzzer.service;

import com.promptfuzzer.config.BrowserTargetConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrowserProfileSmokeServiceTest {

    @Test
    void runUsesStoredProfileAndClosesSession() {
        BrowserService browserService = mock(BrowserService.class);
        BrowserTargetProfileService profileService = mock(BrowserTargetProfileService.class);
        BrowserTargetConfig config = new BrowserTargetConfig();
        when(profileService.configFromProfile("target")).thenReturn(config);
        when(browserService.sendMessageWithCapture(anyLong(), eq("ping")))
                .thenReturn(new BrowserService.BrowserMessageResult("pong", List.of()));
        BrowserProfileSmokeService service = new BrowserProfileSmokeService(browserService, profileService);

        BrowserProfileSmokeService.SmokeResult result = service.run("target", "ping");

        assertThat(result.success()).isTrue();
        assertThat(result.responseText()).isEqualTo("pong");
        verify(browserService).openSession(anyLong(), eq(config));
        verify(browserService).closeSession(anyLong());
    }

    @Test
    void runRejectsAssistantErrorText() {
        BrowserService browserService = mock(BrowserService.class);
        BrowserTargetProfileService profileService = mock(BrowserTargetProfileService.class);
        when(profileService.configFromProfile("target")).thenReturn(new BrowserTargetConfig());
        when(browserService.sendMessageWithCapture(anyLong(), eq("ping")))
                .thenReturn(new BrowserService.BrowserMessageResult(
                        "JSON parse error while calling model", List.of()));
        BrowserProfileSmokeService service = new BrowserProfileSmokeService(browserService, profileService);

        BrowserProfileSmokeService.SmokeResult result = service.run("target", "ping");

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).contains("json parse error");
        verify(browserService).closeSession(anyLong());
    }
}
