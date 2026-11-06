package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.config.BrowserTargetConfig;
import com.promptfuzzer.entity.BrowserTargetProfile;
import com.promptfuzzer.repository.BrowserTargetProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrowserTargetProfileServiceTest {

    @Test
    void appliesAgentDeskDefaultsWhenStoredProfileHasNoSelectors() {
        BrowserTargetProfile profile = new BrowserTargetProfile();
        profile.setTargetType("agentdesk-presales-profile");
        profile.setChatUrl("http://127.0.0.1:8083/support/chat/"
                + "?channelId=72b02ac7c39444a78e0e75dd317bdea3"
                + "&externalId=beichen-customer-001");

        BrowserTargetProfileRepository repository =
                mock(BrowserTargetProfileRepository.class);
        when(repository.findByTargetType("agentdesk-presales-profile"))
                .thenReturn(Optional.of(profile));
        BrowserTargetProfileService service =
                new BrowserTargetProfileService(repository, new ObjectMapper());

        BrowserTargetConfig config =
                service.configFromProfile("agentdesk-presales-profile");

        assertEquals(
                "div[contenteditable=\"true\"][translate=\"no\"]",
                config.getSelectors().get("input"));
        assertEquals(
                "button[aria-label=\"发送\"]",
                config.getSelectors().get("submit"));
        assertEquals(
                "[data-slot=\"message-scroller-content\"]",
                config.getSelectors().get("responseArea"));
        assertEquals(
                "[data-slot=\"message\"][data-align=\"start\"] "
                        + "[data-slot=\"bubble-content\"]",
                config.getSelectors().get("assistantMessage"));
        assertEquals(
                "div[contenteditable=\"true\"][translate=\"no\"]",
                config.getLoginCheck().get("successSelector"));
    }

    @Test
    void appliesVedaDefaultsWhenStoredProfileHasNoSelectors() {
        BrowserTargetProfile profile = new BrowserTargetProfile();
        profile.setTargetType("default");
        profile.setChatUrl("https://veda-claw.tiktok-row.net/chat?zoneId=shop-author-analysis");
        profile.setLoginJson("{\"required\":\"true\"}");

        BrowserTargetProfileRepository repository = mock(BrowserTargetProfileRepository.class);
        when(repository.findByTargetType("default")).thenReturn(Optional.of(profile));
        BrowserTargetProfileService service = new BrowserTargetProfileService(repository, new ObjectMapper());

        BrowserTargetConfig config = service.configFromProfile("default");

        assertEquals("default", config.getTargetType());
        assertEquals(".chat-editor-content[contenteditable=\"true\"]", config.getSelectors().get("input"));
        assertEquals(".chat-input-icon-button", config.getSelectors().get("submit"));
        assertEquals("body", config.getSelectors().get("responseArea"));
        assertEquals(".chat-editor-content[contenteditable=\"true\"]", config.getLoginCheck().get("successSelector"));
    }

    @Test
    void preservesVerifiedStateWhenOnlySelectorJsonOrderDiffers() {
        BrowserTargetProfile profile = new BrowserTargetProfile();
        profile.setId(1L);
        profile.setTargetType("default");
        profile.setChatUrl("https://veda-claw.tiktok-row.net/chat?zoneId=shop-author-analysis");
        profile.setSelectorsJson("{\"input\":\".chat-editor-content[contenteditable=\\\"true\\\"]\","
                + "\"submit\":\".chat-input-icon-button\",\"responseArea\":\"body\","
                + "\"assistantMessage\":\"body\",\"newChat\":\"button[aria-label=\\\"New Chat\\\"]\"}");
        profile.setLoginJson("{\"required\":\"true\"}");
        profile.setLoginCheckJson("{\"successSelector\":\".chat-editor-content[contenteditable=\\\"true\\\"]\"}");
        profile.setAuthState(BrowserTargetProfile.AuthState.VERIFIED);

        BrowserTargetProfileRepository repository = mock(BrowserTargetProfileRepository.class);
        when(repository.findByTargetType("default")).thenReturn(Optional.of(profile));
        when(repository.save(any(BrowserTargetProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        BrowserTargetProfileService service = new BrowserTargetProfileService(repository, new ObjectMapper());

        BrowserTargetConfig config = new BrowserTargetConfig();
        config.setTargetType("default");
        config.setChatUrl("https://veda-claw.tiktok-row.net/chat?zoneId=shop-author-analysis");
        Map<String, String> selectors = new LinkedHashMap<>();
        selectors.put("assistantMessage", "body");
        selectors.put("newChat", "button[aria-label=\"New Chat\"]");
        selectors.put("submit", ".chat-input-icon-button");
        selectors.put("responseArea", "body");
        selectors.put("input", ".chat-editor-content[contenteditable=\"true\"]");
        config.setSelectors(selectors);
        config.setLogin(Map.of("required", "true"));
        config.setLoginCheck(Map.of("successSelector", ".chat-editor-content[contenteditable=\"true\"]"));

        BrowserTargetProfile saved = service.upsertFromConfig(config, "veda-test");

        assertEquals(BrowserTargetProfile.AuthState.VERIFIED, saved.getAuthState());
        verify(repository).save(profile);
    }
}
