package com.promptfuzzer.service;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.promptfuzzer.config.BrowserTargetConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrowserServiceTest {

    @Test
    void autoDetectsVisibleEditableInputWhenSelectorsAreMissing() {
        Page page = mock(Page.class);
        Locator unavailable = unavailableLocator();
        Locator input = mock(Locator.class);
        when(page.locator(anyString())).thenReturn(unavailable);
        when(page.locator("textarea:visible")).thenReturn(input);
        when(input.first()).thenReturn(input);
        when(input.count()).thenReturn(1);
        when(input.isVisible()).thenReturn(true);
        when(input.isEnabled()).thenReturn(true);
        when(input.isEditable()).thenReturn(true);

        BrowserTargetConfig config = new BrowserTargetConfig();
        new BrowserService().resolveDefaultSelectors(page, config);

        assertEquals("textarea:visible", config.getSelectors().get("input"));
    }

    @Test
    void preservesExplicitSelectors() {
        Page page = mock(Page.class);
        BrowserTargetConfig config = new BrowserTargetConfig();
        config.setSelectors(Map.of(
                "input", "#message",
                "submit", "#send"
        ));

        new BrowserService().resolveDefaultSelectors(page, config);

        assertEquals("#message", config.getSelectors().get("input"));
        assertEquals("#send", config.getSelectors().get("submit"));
        verify(page, never()).locator(anyString());
    }

    @Test
    void reportsActionableErrorWhenInputCannotBeDetected() {
        Page page = mock(Page.class);
        Locator unavailable = unavailableLocator();
        when(page.locator(anyString())).thenReturn(unavailable);
        BrowserTargetConfig config = new BrowserTargetConfig();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new BrowserService().resolveDefaultSelectors(page, config));

        assertTrue(error.getMessage().contains("自定义页面"));
        assertTrue(error.getMessage().contains("输入框 CSS 选择器"));
    }

    @Test
    void skipsManualLoginByDefault() {
        assertEquals(false,
                new BrowserService().isManualLoginRequired(new BrowserTargetConfig()));
    }

    @Test
    void requiresManualLoginOnlyWhenExplicitlyConfigured() {
        BrowserTargetConfig config = new BrowserTargetConfig();
        config.setLogin(Map.of("required", "true"));

        assertEquals(true, new BrowserService().isManualLoginRequired(config));
    }

    private Locator unavailableLocator() {
        Locator locator = mock(Locator.class);
        when(locator.first()).thenReturn(locator);
        when(locator.count()).thenReturn(0);
        return locator;
    }
}
