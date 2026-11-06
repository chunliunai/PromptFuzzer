package com.promptfuzzer.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.promptfuzzer.config.BrowserTargetConfig;
import com.promptfuzzer.entity.BrowserTargetProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Service
public class BrowserService {

    private static final Path AUTH_DIR = Path.of("browser-auth");
    private static final List<String> AUTO_INPUT_SELECTORS = List.of(
            "textarea:visible",
            "[contenteditable=\"true\"][role=\"textbox\"]:visible",
            "[contenteditable=\"true\"]:visible",
            "input[type=\"text\"]:visible",
            "input:not([type]):visible",
            "[role=\"textbox\"]:visible"
    );
    private static final List<String> AUTO_SUBMIT_SELECTORS = List.of(
            "button[type=\"submit\"]:visible",
            "input[type=\"submit\"]:visible",
            "button[aria-label*=\"send\" i]:visible",
            "button[title*=\"send\" i]:visible",
            "button[data-testid*=\"send\" i]:visible",
            "button:has(svg[class*=\"lucide-arrow-up\"]):visible"
    );
    private static final List<String> DEFAULT_LOGIN_BLOCKING_SELECTORS = List.of(
            "input[type=\"password\"]:visible",
            "input[autocomplete*=\"one-time-code\" i]:visible",
            "input[name*=\"otp\" i]:visible",
            "input[name*=\"verify\" i]:visible",
            "input[name*=\"verification\" i]:visible",
            "input[name*=\"captcha\" i]:visible",
            "input[placeholder*=\"验证码\" i]:visible",
            "input[placeholder*=\"verification\" i]:visible",
            "input[placeholder*=\"captcha\" i]:visible",
            "[class*=\"captcha\" i]:visible",
            "[id*=\"captcha\" i]:visible",
            "iframe[src*=\"captcha\" i]:visible"
    );

    private Playwright playwright;
    @Autowired(required = false)
    private BrowserTargetProfileService targetProfileService;

    /** sessionId → { page, config } */
    private final Map<Long, SessionState> sessions = new ConcurrentHashMap<>();
    private final Map<String, LoginSession> loginSessions = new ConcurrentHashMap<>();

    private static class SessionState {
        Page page;
        BrowserContext context;
        SharedContext sharedContext;
        BrowserTargetConfig config;
        String targetType;
        Map<String, Map<String, String>> replayHeadersByOrigin = new ConcurrentHashMap<>();
    }

    private static class SharedContext {
        BrowserContext context;
        int refCount = 1;
    }

    private static class LoginSession {
        BrowserContext context;
        Page page;
        BrowserTargetConfig config;
    }

    private record LoginVerification(boolean verified, String reason) {}

    /**
     * Open a browser session for the agent. Manual login is opt-in through
     * targetConfig.login.required; public targets start probing immediately.
     */
    public synchronized void openSession(Long sessionId, BrowserTargetConfig config) {
        SessionState existing = sessions.get(sessionId);
        if (existing != null) {
            log.info("Browser session {} already exists for targetType '{}'", sessionId, existing.targetType);
            return;
        }

        SessionState reusable = findReusableSession(config);
        if (reusable != null) {
            targetProfileService().requireVerifiedForExecution(config);
            createForkedSession(sessionId, reusable, config);
            return;
        }

        BrowserContext context = null;
        try {
            Files.createDirectories(AUTH_DIR);
            initPlaywright();

            targetProfileService().upsertFromConfig(config, null);
            boolean manualLoginRequired = isManualLoginRequired(config);
            if (manualLoginRequired) {
                targetProfileService().requireVerifiedForExecution(config);
            }
            Path userDataDir = targetProfileService().profilePathForConfig(config);

            context = createContext(userDataDir);
            log.info("Browser target '{}' starts execution with manualLoginRequired={}",
                    config.getTargetType(), manualLoginRequired);

            Page page = preparePrimaryPage(context);
            navigateToChat(page, config);
            closeExtraBlankPages(context, page);
            resolveDefaultSelectors(page, config);

            // Verify input selector is visible
            String inputSelector = getSelector(config, "input");
            page.waitForSelector(inputSelector, new Page.WaitForSelectorOptions().setTimeout(10000));

            if (manualLoginRequired) {
                LoginVerification verification = verifyLoginState(page, config);
                if (!verification.verified()) {
                    targetProfileService().markExpired(config.getTargetType(), verification.reason());
                    throw new IllegalStateException("Stored login is not usable for targetType="
                            + config.getTargetType() + ": " + verification.reason());
                }
                targetProfileService().markVerified(config.getTargetType());
            }
            log.info("Browser session {} ready — input '{}' visible", sessionId, inputSelector);

            SessionState state = new SessionState();
            state.page = page;
            state.context = context;
            state.sharedContext = new SharedContext();
            state.sharedContext.context = context;
            state.config = config;
            state.targetType = config.getTargetType();
            sessions.put(sessionId, state);

        } catch (Exception e) {
            if (context != null) {
                try { context.close(); } catch (Exception ignored) {}
            }
            log.error("Failed to open browser session {}", sessionId, e);
            throw new RuntimeException("Browser session open failed: " + e.getMessage(), e);
        }
    }

    public synchronized void forkSession(
            Long sourceSessionId,
            Long targetSessionId,
            BrowserTargetConfig config) {
        targetProfileService().requireVerifiedForExecution(config);
        if (sessions.containsKey(targetSessionId)) {
            return;
        }
        SessionState source = sessions.get(sourceSessionId);
        if (source == null) {
            throw new IllegalStateException(
                    "Source browser session not found: " + sourceSessionId);
        }
        createForkedSession(targetSessionId, source, config);
    }

    /**
     * Check if a browser session exists for the given ID.
     */
    public boolean hasSession(Long sessionId) {
        return sessions.containsKey(sessionId);
    }

    public String cookieHeaderForUrl(Long sessionId, String url) {
        SessionState state = sessions.get(sessionId);
        if (state == null || state.context == null || url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath() != null && !uri.getPath().isBlank()
                    ? uri.getPath() : "/";
            List<Cookie> cookies = state.context.cookies(List.of(url));
            StringBuilder header = new StringBuilder();
            for (Cookie cookie : cookies) {
                if (!matchesCookie(cookie, host, path)) {
                    continue;
                }
                if (header.length() > 0) {
                    header.append("; ");
                }
                header.append(cookie.name).append("=").append(cookie.value);
            }
            return header.toString();
        } catch (Exception e) {
            log.debug("Browser session {} cookie extraction failed: {}", sessionId, e.getMessage());
            return "";
        }
    }

    public Map<String, String> dynamicReplayHeadersForUrl(Long sessionId, String url) {
        SessionState state = sessions.get(sessionId);
        if (state == null || url == null || url.isBlank()) {
            return Map.of();
        }
        try {
            String origin = originKey(url);
            Map<String, String> headers = state.replayHeadersByOrigin.get(origin);
            return headers != null ? new HashMap<>(headers) : Map.of();
        } catch (Exception e) {
            log.debug("Browser session {} dynamic replay header extraction failed: {}",
                    sessionId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Tool: browser_send_message — type message in input, send, wait for AI reply, return text.
     */
    public String sendMessage(Long sessionId, String message) {
        BrowserMessageResult result = sendMessageWithCapture(sessionId, message);
        return result != null ? result.responseText() : null;
    }

    /**
     * Send a message through the browser and return both the DOM-extracted response and
     * a best-effort network capture for the send window. This is observational only:
     * browser behavior and response semantics stay unchanged.
     */
    public BrowserMessageResult sendMessageWithCapture(Long sessionId, String message) {
        SessionState state = sessions.get(sessionId);
        if (state == null) throw new IllegalStateException("No browser session: " + sessionId);

        BrowserTargetConfig config = state.config;
        targetProfileService().requireVerifiedForExecution(config);
        Page page = state.page;
        NetworkCapture capture = new NetworkCapture(state, page, message);

        try {
            String inputSelector = getSelector(config, "input");
            String submitSelector = getSelector(config, "submit");
            waitForInputReady(page, config, 10_000);
            capture.start();

            // Type into input
            Locator input = page.locator(inputSelector).first();
            typeMessage(page, input, inputSelector, message);
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}

            String responseSelector = getResponseSelector(config);
            String assistantSnapshot = readLastAssistantMessageText(page, config);

            // Capture the baseline before submission. Some refusals render so fast
            // that a post-submit snapshot already contains the model response.
            String snapshot = readInnerText(page, responseSelector);
            String bodySnapshot = "body".equals(responseSelector) ? snapshot : readInnerText(page, "body");

            // Veda's icon buttons share broad classes; Enter is the stable send path there.
            submitMessage(page, inputSelector, submitSelector);

            // Let the submitted message render into page DOM before polling.
            try { Thread.sleep(600); } catch (InterruptedException ignored) {}

            log.info("Browser session {} sent message ({} chars), response selector '{}', snapshot {} chars, waiting for response...",
                    sessionId, message.length(), responseSelector, snapshot != null ? snapshot.length() : 0);

            // Wait for AI response to stabilize, diffing against snapshot
            String responseText = waitForResponseStable(page, config, responseSelector, snapshot,
                    bodySnapshot, assistantSnapshot, message);

            log.info("Browser session {} got response ({} chars)", sessionId,
                    responseText != null ? responseText.length() : 0);

            List<TrafficExchange> exchanges = capture.stop();
            logNetworkCaptureSummary(sessionId, exchanges);
            return new BrowserMessageResult(responseText, exchanges);

        } catch (Exception e) {
            log.error("Browser session {} sendMessage failed", sessionId, e);
            List<TrafficExchange> exchanges = capture.stop();
            logNetworkCaptureSummary(sessionId, exchanges);
            return new BrowserMessageResult(null, exchanges);
        }
    }

    /**
     * Tool: browser_new_chat — click new chat button or reload page to reset context.
     */
    public boolean newChat(Long sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return false;

        try {
            String newChatSelector = getSelector(state.config, "newChat");
            if (newChatSelector != null && !newChatSelector.isBlank()) {
                state.page.locator(newChatSelector).first()
                        .click(new Locator.ClickOptions().setTimeout(3000));
                waitForInputReady(state.page, state.config, 10_000);
                log.info("Browser session {} new chat via selector '{}'", sessionId, newChatSelector);
            } else {
                resetToChat(state.page, state.config);
                log.info("Browser session {} new chat via chat navigation reset", sessionId);
            }
            return true;
        } catch (Exception e) {
            log.warn("Browser session {} newChat failed, trying chat navigation reset", sessionId, e);
            try {
                resetToChat(state.page, state.config);
                return true;
            } catch (Exception resetError) {
                log.error("Browser session {} reset failed", sessionId, resetError);
                return false;
            }
        }
    }

    /**
     * Tool: browser_read_page — read text from a specific area, or entire page body.
     */
    public String readPage(Long sessionId, String selector) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return null;

        try {
            if (selector != null && !selector.isBlank()) {
                return state.page.locator(selector).innerText();
            }
            return state.page.locator("body").innerText();
        } catch (Exception e) {
            log.warn("Browser session {} readPage failed for selector '{}'", sessionId, selector, e);
            return null;
        }
    }

    /**
     * Tool: browser_click — click an element on the page.
     */
    public boolean click(Long sessionId, String selector) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return false;

        try {
            state.page.locator(selector).click();
            state.page.waitForLoadState();
            log.info("Browser session {} clicked '{}'", sessionId, selector);
            return true;
        } catch (Exception e) {
            log.warn("Browser session {} click failed for '{}'", sessionId, selector, e);
            return false;
        }
    }

    /**
     * Close browser session and free resources.
     */
    public synchronized void closeSession(Long sessionId) {
        SessionState state = sessions.remove(sessionId);
        if (state != null) {
            try {
                state.page.close();
            } catch (Exception e) {
                log.warn("Error closing browser page for session {}", sessionId, e);
            }

            state.sharedContext.refCount--;
            if (state.sharedContext.refCount == 0) {
                try {
                    state.sharedContext.context.close();
                } catch (Exception e) {
                    log.warn("Error closing browser context for session {}", sessionId, e);
                }
            }
            log.info("Browser session {} closed, shared context refs={}",
                    sessionId, state.sharedContext.refCount);
        }
    }

    public synchronized BrowserTargetProfile startLogin(BrowserTargetConfig config) {
        if (config == null || config.getChatUrl() == null || config.getChatUrl().isBlank()) {
            throw new IllegalArgumentException("targetConfig.chatUrl is required");
        }
        if (config.getTargetType() == null || config.getTargetType().isBlank()) {
            config.setTargetType("default");
        }
        closeLoginSession(config.getTargetType());
        BrowserContext context = null;
        try {
            Files.createDirectories(AUTH_DIR);
            initPlaywright();
            BrowserTargetProfile profile = targetProfileService().markWaitingLogin(config);
            context = createContext(targetProfileService().profilePathForConfig(config));
            Page page = preparePrimaryPage(context);
            navigateToChat(page, config);

            LoginSession loginSession = new LoginSession();
            loginSession.context = context;
            loginSession.page = page;
            loginSession.config = config;
            loginSessions.put(config.getTargetType(), loginSession);
            log.info("Manual login session opened for targetType '{}'", config.getTargetType());
            return profile;
        } catch (Exception e) {
            if (context != null) {
                try { context.close(); } catch (Exception ignored) {}
            }
            targetProfileService().markFailed(config.getTargetType(), e.getMessage());
            throw new RuntimeException("Manual login start failed: " + e.getMessage(), e);
        }
    }

    public synchronized BrowserTargetProfile signalAuthReady(String targetType) {
        LoginSession loginSession = loginSessions.get(targetType);
        BrowserTargetConfig config = loginSession != null
                ? loginSession.config
                : targetProfileService().configFromProfile(targetType);
        BrowserContext context = null;
        boolean ownsContext = false;
        try {
            targetProfileService().markVerifying(config.getTargetType());
            Page page;
            if (loginSession != null && loginSession.page != null && !loginSession.page.isClosed()) {
                page = loginSession.page;
            } else {
                Files.createDirectories(AUTH_DIR);
                initPlaywright();
                context = createContext(targetProfileService().profilePathForConfig(config));
                ownsContext = true;
                page = preparePrimaryPage(context);
            }

            navigateToChatForVerification(page, config);
            LoginVerification verification = verifyLoginState(page, config);
            if (!verification.verified()) {
                return targetProfileService().markFailed(config.getTargetType(), verification.reason());
            }

            BrowserTargetProfile profile = targetProfileService().markVerified(config.getTargetType());
            closeLoginSession(config.getTargetType());
            if (ownsContext && context != null) {
                context.close();
            }
            log.info("Manual login verified for targetType '{}'", config.getTargetType());
            return profile;
        } catch (Exception e) {
            return targetProfileService().markFailed(config.getTargetType(), e.getMessage());
        } finally {
            if (ownsContext && context != null) {
                try { context.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Legacy endpoint support. Prefer signalAuthReady(targetType) from the UI.
     */
    public BrowserTargetProfile signalAuthReady() {
        if (loginSessions.size() == 1) {
            String targetType = loginSessions.keySet().iterator().next();
            return signalAuthReady(targetType);
        }
        throw new IllegalStateException("targetType is required when zero or multiple login sessions are active");
    }

    public synchronized BrowserTargetProfile clearAuthState(String targetType) {
        closeLoginSession(targetType);
        targetProfileService().findByTargetType(targetType).ifPresent(profile -> {
            if (profile.getProfilePath() != null && !profile.getProfilePath().isBlank()) {
                deleteDirectory(Path.of(profile.getProfilePath()));
            }
        });
        return targetProfileService().resetAuthState(targetType);
    }

    /**
     * Clean up the entire browser instance (called on app shutdown).
     */
    public void shutdown() {
        new ArrayList<>(sessions.keySet()).forEach(this::closeSession);
        if (playwright != null) { try { playwright.close(); } catch (Exception ignored) {} }
        log.info("BrowserService shut down");
    }

    // ---- private helpers ----

    private synchronized void initPlaywright() {
        if (playwright != null) return;
        playwright = Playwright.create();
        log.info("Playwright initialized");
    }

    private BrowserTargetProfileService targetProfileService() {
        if (targetProfileService == null) {
            throw new IllegalStateException("BrowserTargetProfileService is not available");
        }
        return targetProfileService;
    }

    private void waitForInputReady(Page page, BrowserTargetConfig config, int timeoutMs) {
        String inputSelector = getSelector(config, "input");
        if (inputSelector == null || inputSelector.isBlank()) {
            resolveDefaultSelectors(page, config);
            inputSelector = getSelector(config, "input");
        }
        page.waitForSelector(inputSelector, new Page.WaitForSelectorOptions().setTimeout(timeoutMs));
        Locator input = page.locator(inputSelector).first();
        input.waitFor(new Locator.WaitForOptions().setTimeout(timeoutMs));

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (input.isVisible() && input.isEnabled()) {
                    return;
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
        }
        throw new RuntimeException("Input selector is not enabled: " + inputSelector);
    }

    private boolean matchesCookie(Cookie cookie, String host, String path) {
        if (cookie == null || cookie.name == null || cookie.value == null) {
            return false;
        }
        if (host != null && cookie.domain != null && !cookie.domain.isBlank()) {
            String cookieDomain = cookie.domain.startsWith(".")
                    ? cookie.domain.substring(1) : cookie.domain;
            if (!host.equals(cookieDomain) && !host.endsWith("." + cookieDomain)) {
                return false;
            }
        }
        if (path != null && cookie.path != null && !cookie.path.isBlank()
                && !path.startsWith(cookie.path)) {
            return false;
        }
        return true;
    }

    private String originKey(String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "https";
        String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
        int port = uri.getPort();
        return port > 0 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
    }

    private void submitMessage(Page page, String inputSelector, String submitSelector) {
        if (shouldSubmitWithEnterFirst(inputSelector, submitSelector)) {
            pressEnterToSubmit(page, inputSelector);
            log.info("Browser message submitted via Enter preferred for input '{}'", inputSelector);
            return;
        }

        if (submitSelector != null && !submitSelector.isBlank()) {
            try {
                Locator submitButtons = page.locator(submitSelector);
                int count = submitButtons.count();
                for (int i = 0; i < count; i++) {
                    Locator submit = submitButtons.nth(i);
                    if (submit.isVisible(new Locator.IsVisibleOptions().setTimeout(1500))) {
                        if (submit.isEnabled()) {
                            submit.click(new Locator.ClickOptions().setTimeout(2000));
                            log.info("Browser message submitted via selector '{}' at index {}", submitSelector, i);
                            return;
                        }
                        log.debug("Submit selector '{}' matched disabled button at index {}", submitSelector, i);
                    }
                }
            } catch (Exception e) {
                log.debug("Submit button click path unavailable, falling back to Enter: {}", e.getMessage());
            }
        }

        pressEnterToSubmit(page, inputSelector);
        log.info("Browser message submitted via Enter fallback");
    }

    private boolean shouldSubmitWithEnterFirst(String inputSelector, String submitSelector) {
        String input = inputSelector == null ? "" : inputSelector.toLowerCase();
        String submit = submitSelector == null ? "" : submitSelector.toLowerCase();
        return input.contains("chat-editor-content")
                || submit.equals(".chat-input-icon-button");
    }

    private void pressEnterToSubmit(Page page, String inputSelector) {
        if (inputSelector != null && !inputSelector.isBlank()) {
            page.locator(inputSelector).first().press("Enter");
        } else {
            page.keyboard().press("Enter");
        }
    }

    private void typeMessage(Page page, Locator input, String inputSelector, String message) {
        input.click(new Locator.ClickOptions().setTimeout(3000));
        if (isContentEditableInput(inputSelector)) {
            try {
                input.fill("");
            } catch (Exception e) {
                log.debug("Contenteditable clear via fill failed, using keyboard clear: {}", e.getMessage());
            }
            page.keyboard().press("Meta+A");
            page.keyboard().press("Backspace");
            page.keyboard().insertText(message);
            log.info("Browser message typed via keyboard insertText for input '{}'", inputSelector);
            return;
        }
        input.fill(message);
    }

    private boolean isContentEditableInput(String inputSelector) {
        if (inputSelector == null) {
            return false;
        }
        String lower = inputSelector.toLowerCase();
        return lower.contains("contenteditable")
                || lower.contains("prosemirror")
                || lower.contains("chat-editor-content");
    }

    private void resetToChat(Page page, BrowserTargetConfig config) {
        navigateToChat(page, config);
        resolveDefaultSelectors(page, config);
        waitForInputReady(page, config, 15_000);
    }

    void resolveDefaultSelectors(Page page, BrowserTargetConfig config) {
        Map<String, String> selectors = config.getSelectors() == null
                ? new HashMap<>()
                : new HashMap<>(config.getSelectors());
        config.setSelectors(selectors);

        if (isBlank(selectors.get("input"))) {
            String inputSelector = findUsableSelector(page, AUTO_INPUT_SELECTORS, true);
            if (inputSelector == null) {
                throw new IllegalStateException(
                        "未自动识别到聊天输入框，请选择“自定义页面”并填写输入框 CSS 选择器");
            }
            selectors.put("input", inputSelector);
            log.info("Auto-detected browser input selector '{}'", inputSelector);
        }

        if (isBlank(selectors.get("submit"))) {
            String submitSelector = findUsableSelector(page, AUTO_SUBMIT_SELECTORS, false);
            if (submitSelector != null) {
                selectors.put("submit", submitSelector);
                log.info("Auto-detected browser submit selector '{}'", submitSelector);
            }
        }
    }

    private String findUsableSelector(Page page, List<String> candidates, boolean requireEditable) {
        for (String selector : candidates) {
            try {
                Locator locator = page.locator(selector).first();
                if (locator.count() > 0
                        && locator.isVisible()
                        && locator.isEnabled()
                        && (!requireEditable || locator.isEditable())) {
                    return selector;
                }
            } catch (Exception e) {
                log.debug("Auto selector '{}' unavailable: {}", selector, e.getMessage());
            }
        }
        return null;
    }

    private String getSelector(BrowserTargetConfig config, String key) {
        if (config == null || config.getSelectors() == null) {
            return null;
        }
        return config.getSelectors().get(key);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    boolean isManualLoginRequired(BrowserTargetConfig config) {
        if (targetProfileService != null) {
            return targetProfileService.isManualLoginRequired(config);
        }
        if (config == null || config.getLogin() == null) {
            return false;
        }
        return Boolean.parseBoolean(config.getLogin().getOrDefault("required", "false"));
    }

    private SessionState findReusableSession(BrowserTargetConfig config) {
        if (config == null || config.getTargetType() == null
                || config.getTargetType().isBlank()) {
            return null;
        }
        for (SessionState state : sessions.values()) {
            if (config.getTargetType().equals(state.targetType)
                    && config.getChatUrl().equals(state.config.getChatUrl())
                    && state.context != null
                    && state.page != null
                    && !state.page.isClosed()) {
                return state;
            }
        }
        return null;
    }

    private void createForkedSession(
            Long sessionId,
            SessionState source,
            BrowserTargetConfig config) {
        Page page = null;
        try {
            page = source.context.newPage();
            navigateToChat(page, config);
            resolveDefaultSelectors(page, config);
            String inputSelector = getSelector(config, "input");
            page.waitForSelector(inputSelector,
                    new Page.WaitForSelectorOptions().setTimeout(10000));

            source.sharedContext.refCount++;
            SessionState state = new SessionState();
            state.page = page;
            state.context = source.context;
            state.sharedContext = source.sharedContext;
            state.config = config;
            state.targetType = config.getTargetType();
            source.replayHeadersByOrigin.forEach((origin, headers) ->
                    state.replayHeadersByOrigin.put(origin, new HashMap<>(headers)));
            sessions.put(sessionId, state);
            log.info("Browser session {} opened isolated page for targetType '{}', shared context refs={}",
                    sessionId, state.targetType, state.sharedContext.refCount);
        } catch (Exception e) {
            if (page != null) {
                try { page.close(); } catch (Exception ignored) {}
            }
            throw new RuntimeException(
                    "Browser session fork failed: " + e.getMessage(), e);
        }
    }

    private Page preparePrimaryPage(BrowserContext context) {
        List<Page> pages = context.pages();
        if (!pages.isEmpty()) {
            return pages.get(0);
        }
        return context.newPage();
    }

    private void closeExtraBlankPages(BrowserContext context, Page primaryPage) {
        for (Page page : context.pages()) {
            if (page == primaryPage || page.isClosed()) {
                continue;
            }
            String url = page.url();
            if (url == null || url.isBlank() || "about:blank".equals(url)) {
                try {
                    page.close();
                } catch (Exception e) {
                    log.debug("Failed to close extra blank page: {}", e.getMessage());
                }
            }
        }
    }

    private LoginVerification verifyLoginState(Page page, BrowserTargetConfig config) {
        String currentUrl = page.url();
        if (looksLikeLoginUrl(currentUrl)) {
            return new LoginVerification(false, "Current URL still looks like a login page: " + currentUrl);
        }

        String loginPageSelector = loginCheckValue(config, "loginPageSelector");
        if (isSelectorVisible(page, loginPageSelector)) {
            return new LoginVerification(false, "Login page selector is still visible: " + loginPageSelector);
        }
        String passwordSelector = loginCheckValue(config, "passwordSelector");
        if (isSelectorVisible(page, passwordSelector)) {
            return new LoginVerification(false, "Password selector is still visible: " + passwordSelector);
        }
        String captchaSelector = loginCheckValue(config, "captchaSelector");
        if (isSelectorVisible(page, captchaSelector)) {
            return new LoginVerification(false, "Captcha selector is still visible: " + captchaSelector);
        }
        String defaultBlockingSelector = visibleDefaultLoginBlockingSelector(page);
        if (!isBlank(defaultBlockingSelector)) {
            return new LoginVerification(false, "Login verification blocker is still visible: " + defaultBlockingSelector);
        }

        String successSelector = loginCheckValue(config, "successSelector");
        if (!isBlank(successSelector) && !waitForSelectorVisible(page, successSelector, 12_000)) {
            return new LoginVerification(false, "Success selector is not visible: " + successSelector);
        }

        try {
            resolveDefaultSelectors(page, config);
            String inputSelector = getSelector(config, "input");
            if (isBlank(inputSelector) || !waitForSelectorVisible(page, inputSelector, 12_000)) {
                return new LoginVerification(false, "Chat input selector is not visible");
            }
            if (isBlank(successSelector) && !looksLikeChatInputSelector(inputSelector)) {
                return new LoginVerification(false,
                        "Chat input selector is too generic for login verification; configure loginCheck.successSelector");
            }
        } catch (Exception e) {
            return new LoginVerification(false, "Chat input verification failed: " + e.getMessage());
        }
        return new LoginVerification(true, null);
    }

    private boolean looksLikeLoginUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.contains("/login")
                || lower.contains("/signin")
                || lower.contains("/sign-in")
                || lower.contains("/auth")
                || lower.contains("/oauth")
                || lower.contains("/sso")
                || lower.contains("captcha")
                || lower.contains("verify");
    }

    private String visibleDefaultLoginBlockingSelector(Page page) {
        for (String selector : DEFAULT_LOGIN_BLOCKING_SELECTORS) {
            if (isSelectorVisible(page, selector)) {
                return selector;
            }
        }
        return null;
    }

    private boolean looksLikeChatInputSelector(String selector) {
        if (selector == null) {
            return false;
        }
        String lower = selector.toLowerCase();
        return lower.contains("textarea")
                || lower.contains("contenteditable")
                || lower.contains("role=\"textbox\"")
                || lower.contains("role='textbox'")
                || lower.contains("[role=textbox]");
    }

    private String loginCheckValue(BrowserTargetConfig config, String key) {
        if (config == null) {
            return null;
        }
        if (config.getLoginCheck() != null && !isBlank(config.getLoginCheck().get(key))) {
            return config.getLoginCheck().get(key);
        }
        if (config.getLogin() != null && !isBlank(config.getLogin().get(key))) {
            return config.getLogin().get(key);
        }
        return null;
    }

    private boolean isSelectorVisible(Page page, String selector) {
        if (isBlank(selector)) {
            return false;
        }
        try {
            return page.locator(selector).first().isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean waitForSelectorVisible(Page page, String selector, int timeoutMs) {
        if (isBlank(selector)) {
            return false;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isSelectorVisible(page, selector)) {
                return true;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void navigateToChat(Page page, BrowserTargetConfig config) {
        int timeoutMs = Math.max(config.getWaitTimeoutMs(), 45_000);
        page.navigate(config.getChatUrl(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(timeoutMs));
    }

    private void navigateToChatForVerification(Page page, BrowserTargetConfig config) {
        navigateToChat(page, config);
        long deadline = System.currentTimeMillis() + 10_000;
        String successSelector = loginCheckValue(config, "successSelector");
        while (System.currentTimeMillis() < deadline) {
            if (!looksLikeLoginUrl(page.url()) && waitForSelectorVisible(page, successSelector, 250)) {
                return;
            }
            if (!looksLikeLoginUrl(page.url()) && isBlank(successSelector)) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Auto-detect system proxy from env vars (HTTP_PROXY / HTTPS_PROXY / http_proxy / https_proxy).
     * Returns proxy URL like "http://127.0.0.1:7890" or null if not configured.
     */
    private String detectSystemProxy() {
        for (String key : new String[]{"HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy", "ALL_PROXY", "all_proxy"}) {
            String val = System.getenv(key);
            if (val != null && !val.isBlank()) {
                log.info("Detected proxy from env {}={}", key, val);
                return val;
            }
        }
        // Check Java system properties
        for (String key : new String[]{"https.proxyHost", "http.proxyHost"}) {
            String host = System.getProperty(key);
            if (host != null && !host.isBlank()) {
                String port = System.getProperty(key.replace("Host", "Port"), "8080");
                String url = "http://" + host + ":" + port;
                log.info("Detected proxy from property {}={}", key, url);
                return url;
            }
        }
        return null;
    }

    /**
     * Create a BrowserContext with anti-detection measures.
     * Uses a persistent user data directory to look like a normal browser.
     */
    private BrowserContext createContext(Path userDataDir) {
        // Build args with optional proxy
        List<String> argsList = new ArrayList<>(Arrays.asList(
                "--disable-blink-features=AutomationControlled",
                "--disable-features=IsolateOrigins,site-per-process",
                "--no-sandbox"
        ));
        String proxyUrl = detectSystemProxy();
        if (proxyUrl != null) {
            argsList.add("--proxy-server=" + proxyUrl);
            log.info("Using proxy server: {}", proxyUrl);
        }

        BrowserType.LaunchPersistentContextOptions options =
                new BrowserType.LaunchPersistentContextOptions()
                        .setHeadless(false)
                        .setViewportSize(1280, 800)
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                        .setArgs(argsList);

        BrowserContext context = playwright.chromium()
                .launchPersistentContext(userDataDir, options);

        log.info("Using persistent context from {}", userDataDir);

        return context;
    }

    private void closeLoginSession(String targetType) {
        LoginSession loginSession = loginSessions.remove(targetType);
        if (loginSession != null && loginSession.context != null) {
            try {
                loginSession.context.close();
            } catch (Exception e) {
                log.warn("Error closing login session for targetType '{}'", targetType, e);
            }
        }
    }

    private void deleteDirectory(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to delete " + item, e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete profile directory " + path, e);
        }
    }

    /**
     * Wait for AI response text to stabilize, returning only new content beyond the snapshot.
     *
     * Strategy: poll configured response area every 800ms, diff against pre-submission snapshot.
     * The diff excludes static page UI and conversation history already present in the
     * configured area, returning only content that appeared after submission.
     */
    private String waitForResponseStable(Page page, BrowserTargetConfig config, String responseSelector,
                                         String snapshot, String bodySnapshot,
                                         String assistantSnapshot, String submittedMessage) {
        int timeoutMs = config.getWaitTimeoutMs();

        // Initial delay for AI to start responding (streaming may take 1-2s to begin)
        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

        String lastDiff = "";
        DiffResult lastNonBlankDiff = new DiffResult("none", "");
        int stableChecks = 0;
        long firstContentAt = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            try {
                if (firstContentAt > 0 && System.currentTimeMillis() - firstContentAt > 20000) {
                    String latestText = lastNonBlankDiff.text();
                    log.warn("Browser response did not fully stabilize after 20s; returning latest {} diff ({} chars): {}",
                            lastNonBlankDiff.source(), latestText.length(), preview(latestText, 120));
                    return sanitizeText(latestText);
                }

                DiffResult diff = readResponseDiff(page, config, responseSelector, snapshot,
                        bodySnapshot, assistantSnapshot, submittedMessage);
                String aiContent = normalizeResponseText(diff.text(), submittedMessage);

                // L7-style word games often produce very short one-word answers.
                // Treat any non-empty diff as arrived, then rely on stability below.
                boolean aiHasResponded = !aiContent.isBlank();
                if (aiHasResponded) {
                    lastNonBlankDiff = new DiffResult(diff.source(), aiContent);
                    if (firstContentAt == 0) {
                        firstContentAt = System.currentTimeMillis();
                        log.info("Browser response first appeared from {} diff ({} chars): {}",
                                diff.source(), aiContent.length(), preview(aiContent, 120));
                    }
                }

                if (aiHasResponded
                        && aiContent.equals(lastDiff)
                        && isResponseCompleteEnough(aiContent)) {
                    stableChecks++;
                    if (stableChecks >= 4) {
                        // Content stable across 5 consecutive reads (4.0s) and looks complete
                        log.info("Stable browser response from {} diff ({} chars): {}",
                                diff.source(), aiContent.length(), preview(aiContent, 120));
                        return sanitizeText(aiContent);
                    }
                } else {
                    stableChecks = 0;
                    lastDiff = aiContent;
                }

                try { Thread.sleep(800); } catch (InterruptedException ignored) {}

            } catch (Exception e) {
                log.debug("Error while polling for response: {}", e.getMessage());
                stableChecks = 0;
            }
        }

        String currentBody = readInnerText(page, responseSelector);
        log.warn("Response wait timed out after {}ms, selector='{}', snapshot={} current={} chars",
                timeoutMs, responseSelector,
                snapshot != null ? snapshot.length() : 0,
                currentBody != null ? currentBody.length() : 0);
        DiffResult finalDiff = !lastNonBlankDiff.text().isBlank()
                ? lastNonBlankDiff
                : readResponseDiff(page, config, responseSelector, snapshot,
                        bodySnapshot, assistantSnapshot, submittedMessage);
        if (looksLikeSubmittedText(finalDiff.text(), submittedMessage)) {
            log.warn("Final browser diff looks like submitted user text; dropping it: {}",
                    preview(finalDiff.text(), 120));
            return "";
        }
        log.info("Final browser response diff from {} returned {} chars: {}",
                finalDiff.source(), finalDiff.text().length(), preview(finalDiff.text(), 120));
        return sanitizeText(finalDiff.text());
    }

    private String normalizeResponseText(String text, String submittedMessage) {
        if (text == null || text.isBlank()) return "";
        String cleaned = text;
        cleaned = removeAfterPattern(cleaned, "(?is)Click\\s+to\\s+submit\\s+your\\s+answer.*");
        if (submittedMessage != null && !submittedMessage.isBlank()) {
            cleaned = cleaned.replace(submittedMessage, "");
        }

        StringBuilder normalized = new StringBuilder();
        for (String line : cleaned.split("\n")) {
            String norm = normalizeLine(line);
            if (!norm.isBlank()
                    && !isRoleLabelOnly(norm)
                    && !looksLikeSubmittedText(norm, submittedMessage)) {
                normalized.append(norm).append("\n");
            }
        }
        return normalized.toString().trim();
    }

    private String removeAfterPattern(String text, String regex) {
        if (text == null || text.isBlank()) return "";
        return text.replaceFirst(regex, "");
    }

    private String getResponseSelector(BrowserTargetConfig config) {
        String responseSelector = getSelector(config, "responseArea");
        if (responseSelector == null || responseSelector.isBlank()) {
            return "body";
        }
        return responseSelector;
    }

    private DiffResult readResponseDiff(Page page, BrowserTargetConfig config, String responseSelector,
                                        String snapshot, String bodySnapshot,
                                        String assistantSnapshot, String submittedMessage) {
        String assistantSelector = getAssistantSelector(config);
        if (!assistantSelector.isBlank()) {
            String assistantText = readLastAssistantMessageText(page, config);
            if (!assistantText.isBlank() && !assistantText.equals(assistantSnapshot)) {
                String normalizedAssistant = normalizeResponseText(assistantText, submittedMessage);
                if (!normalizedAssistant.isBlank()) {
                    return new DiffResult(
                            "assistantMessage:" + assistantSelector, normalizedAssistant);
                }
            }
            // An explicit assistant selector is authoritative. Falling back to body
            // here can mistake transient chat controls for a model response.
            return new DiffResult("none", "");
        }

        String currentPrimary = readInnerText(page, responseSelector);
        String primaryDiff = diffText(snapshot, currentPrimary);
        if (primaryDiff != null && !primaryDiff.isBlank()) {
            String normalizedPrimary = normalizeResponseText(primaryDiff, submittedMessage);
            if (!normalizedPrimary.isBlank()) {
                return new DiffResult("responseArea:" + responseSelector, normalizedPrimary);
            }
        }

        if (!"body".equals(responseSelector)) {
            String currentBody = readInnerText(page, "body");
            String bodyDiff = diffText(bodySnapshot, currentBody);
            if (bodyDiff != null && !bodyDiff.isBlank()) {
                String normalizedBody = normalizeResponseText(bodyDiff, submittedMessage);
                if (!normalizedBody.isBlank()) {
                    return new DiffResult("body-fallback", normalizedBody);
                }
            }
        }

        return new DiffResult("none", "");
    }

    private String getAssistantSelector(BrowserTargetConfig config) {
        String selector = getSelector(config, "assistantMessage");
        return selector != null ? selector : "";
    }

    private String readLastAssistantMessageText(Page page, BrowserTargetConfig config) {
        String selector = getAssistantSelector(config);
        if (selector.isBlank()) return "";

        try {
            Locator messages = page.locator(selector);
            int count = messages.count();
            for (int i = count - 1; i >= 0; i--) {
                Locator candidate = messages.nth(i);
                try {
                    if (!candidate.isVisible()) continue;
                    String text = candidate.innerText(new Locator.InnerTextOptions().setTimeout(1000));
                    String normalized = normalizeResponseText(text, null);
                    if (!normalized.isBlank()) {
                        return normalized;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.debug("assistantMessage selector '{}' unavailable: {}", selector, e.getMessage());
        }
        return "";
    }

    private String readInnerText(Page page, String selector) {
        try {
            return page.locator(selector).innerText(new Locator.InnerTextOptions().setTimeout(1000));
        } catch (Exception e) {
            log.debug("Selector '{}' text unavailable: {}", selector, e.getMessage());
            return "";
        }
    }

    private String preview(String text, int maxLen) {
        if (text == null) return "";
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLen) return normalized;
        return normalized.substring(0, maxLen) + "...";
    }

    private boolean looksLikeSubmittedText(String text, String submittedMessage) {
        if (text == null || text.isBlank() || submittedMessage == null || submittedMessage.isBlank()) {
            return false;
        }

        String a = normalizeForSimilarity(text);
        String b = normalizeForSimilarity(submittedMessage);
        if (a.isBlank() || b.isBlank()) {
            return false;
        }
        if (a.equals("user") || a.startsWith("user" + b) || a.contains(b) || b.contains(a)) {
            return true;
        }

        int common = longestCommonSubstringLength(a, b);
        double ratio = (double) common / Math.max(1, Math.min(a.length(), b.length()));
        return ratio >= 0.82 && Math.min(a.length(), b.length()) >= 8;
    }

    private boolean isRoleLabelOnly(String text) {
        if (text == null) return false;
        String normalized = text.trim().replaceAll("[:：]", "").toLowerCase();
        return normalized.equals("user")
                || normalized.equals("assistant")
                || normalized.equals("ai")
                || normalized.equals("model");
    }

    private String normalizeForSimilarity(String text) {
        if (text == null) return "";
        return normalizeLine(text)
                .replaceAll("(?i)^user\\s*", "")
                .replaceAll("\\s+", "")
                .replaceAll("[`'\"“”‘’\\[\\]{}()（）【】<>《》|:：,，.。;；!！?？\\-_/\\\\]", "")
                .toLowerCase();
    }

    private int longestCommonSubstringLength(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int best = 0;
        for (int i = 1; i <= a.length(); i++) {
            int[] curr = new int[b.length() + 1];
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    curr[j] = prev[j - 1] + 1;
                    if (curr[j] > best) best = curr[j];
                }
            }
            prev = curr;
        }
        return best;
    }

    private record DiffResult(String source, String text) {}

    /**
     * Return only the text that appeared after the snapshot was taken.
     *
     * Uses line-level set difference with normalization: strips dynamic page
     * counters (layer counts, token numbers, etc.) from each line before
     * comparing. Lines in the current text whose normalized form doesn't
     * appear in the normalized snapshot are considered new AI response content.
     *
     * This handles chat pages where counters, token counts, or transient control
     * text change between polls, breaking naive prefix-match diffs.
     */
    private String diffText(String snapshot, String currentText) {
        if (snapshot == null || snapshot.isEmpty()) return currentText;
        if (currentText == null || currentText.isEmpty()) return "";

        // Fast path: exact match means nothing changed
        if (currentText.equals(snapshot)) return "";

        // Build a set of normalized snapshot lines for membership testing
        java.util.Set<String> snapshotSet = new java.util.HashSet<>();
        for (String line : snapshot.split("\n")) {
            String norm = normalizeLine(line);
            if (!norm.isEmpty()) {
                snapshotSet.add(norm);
            }
        }

        // Collect lines in current text whose normalized form isn't in the snapshot
        StringBuilder newContent = new StringBuilder();
        for (String line : currentText.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String norm = normalizeLine(trimmed);
            if (!norm.isEmpty() && !snapshotSet.contains(norm)) {
                newContent.append(trimmed).append("\n");
            }
        }

        String result = newContent.toString().trim();
        if (!result.isEmpty()) {
            return result;
        }

        // Nothing new found — page may have restructured (e.g. new chat).
        // Return empty rather than full page text to avoid pollution.
        log.debug("Line diff found no new content (snapshot {} lines)", snapshotSet.size());
        return "";
    }

    /**
     * Strip dynamic counters from a line so it can be compared across polls.
     * Chat page lines often change in these ways between captures:
     *   "01 Role 23 tok ..."  →  "01 Role 25 tok ..."  (token count)
     *   "3 layers | 319 / 4,096" → "5 layers | 356 / 4,096"
     *   "Click to submit your answer" / "Type your attack message" (transient UI)
     */
    private String normalizeLine(String line) {
        if (line == null) return "";
        String cleaned = line
                .replaceAll("[\\u200B-\\u200D\\uFEFF]", "")
                .replaceFirst("(?is)Click\\s+to\\s+submit\\s+your\\s+answer.*", "");
        String normalized = cleaned
                // "01 User 23 tok ..." → "User ..."
                .replaceFirst("^\\d{1,3}\\s+", "")
                // Drop volatile token counters from chat transcript lines.
                .replaceAll("\\s+\\d+\\s+tok", "")
                // Generic trace/progress counters.
                .replaceAll("\\d+\\s+layers\\s+\\|\\s+\\d+ / [\\d,]+", "")
                // Standalone/current token budget counters, e.g. "1,113 / 8,192".
                .replaceAll("[\\u200B\\s]*\\d[\\d,]*\\s*/\\s*[\\d,]+", "")
                // Some pages initially render only the maximum, e.g. "​ / 8,192".
                .replaceAll("[\\u200B\\s]*/\\s*[\\d,]+", "")
                // Loading/control labels are not model responses.
                .replaceAll("\\bHint\\b", "")
                .replaceAll("\\bThinking\\b", "")
                // Transient UI prompts
                .replaceAll("Click to submit your answer\\s*", "")
                .replaceAll("Type your attack message\\s*", "")
                .trim();
        if (normalized.matches("\\d{1,3}")) {
            return "";
        }
        return normalized;
    }

    /**
     * Check if text looks complete (not cut off mid-word). Returns false if the text
     * ends with what looks like an incomplete word — which means the AI is still streaming.
     */
    private boolean isTextComplete(String text) {
        if (text == null || text.length() < 30) return false;
        // Ends with sentence-ending punctuation → likely complete
        char lastChar = text.charAt(text.length() - 1);
        if (lastChar == '.' || lastChar == '?' || lastChar == '!' ||
                lastChar == '。' || lastChar == '？' || lastChar == '！' ||
                lastChar == ')' || lastChar == '"' || lastChar == '…') {
            return true;
        }
        // Ends with a letter or digit → might be mid-word, don't trust it
        if (Character.isLetterOrDigit(lastChar)) {
            return false;
        }
        // Ends with space/newline/etc → acceptable
        return true;
    }

    private boolean isResponseCompleteEnough(String text) {
        if (text == null || text.isBlank()) return false;
        // Short answers are expected for constrained word games and may end in a bare letter.
        if (text.trim().length() <= 80) return true;
        return isTextComplete(text);
    }

    /**
     * Trim and normalize extracted text.
     */
    private String sanitizeText(String text) {
        if (text == null || text.isBlank()) return "";
        // Remove excessive whitespace
        return text.replaceAll("\\s+", " ").trim();
    }

    private void logNetworkCaptureSummary(Long sessionId, List<TrafficExchange> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            log.info("Browser session {} network capture found no candidate exchanges", sessionId);
            return;
        }
        TrafficExchange top = exchanges.get(0);
        log.info("Browser session {} network capture candidates={}, topScore={}, top={} {}",
                sessionId, exchanges.size(), top.score(), top.method(), top.urlPreview());
    }

    private class NetworkCapture {
        private static final int MAX_CAPTURED_EXCHANGES = 20;
        private static final int MAX_BODY_PREVIEW_CHARS = 1200;
        private static final int MAX_HEADER_PREVIEW_CHARS = 1200;

        private final SessionState sessionState;
        private final Page page;
        private final String submittedMessage;
        private final Map<Request, MutableTrafficExchange> exchanges = new ConcurrentHashMap<>();
        private Consumer<Request> requestHandler;
        private Consumer<Request> requestFinishedHandler;
        private boolean started;

        private NetworkCapture(SessionState sessionState, Page page, String submittedMessage) {
            this.sessionState = sessionState;
            this.page = page;
            this.submittedMessage = submittedMessage;
        }

        private void start() {
            if (started) {
                return;
            }
            started = true;
            requestHandler = request -> {
                try {
                    MutableTrafficExchange exchange = exchanges.computeIfAbsent(
                            request, ignored -> new MutableTrafficExchange());
                    exchange.method = request.method();
                    exchange.url = request.url();
                    exchange.resourceType = request.resourceType();
                    exchange.requestHeadersRaw = formatHeaders(request.headers());
                    exchange.requestHeadersPreview = redactHeaders(request.headers());
                    String postData = request.postData();
                    exchange.requestBodyRaw = postData != null ? postData : "";
                    exchange.requestBodyPreview = redactBody(postData);
                    exchange.requestBodyChars = postData != null ? postData.length() : 0;
                    exchange.promptInRequest = containsSubmittedMessage(postData);
                    if (exchange.promptInRequest) {
                        rememberReplayHeaders(request.url(), request.headers());
                    }
                    exchange.startedAtMs = System.currentTimeMillis();
                    exchange.score = score(exchange);
                } catch (Exception e) {
                    log.debug("Browser network request capture failed: {}", e.getMessage());
                }
            };
            requestFinishedHandler = request -> {
                try {
                    MutableTrafficExchange exchange = exchanges.computeIfAbsent(
                            request, ignored -> new MutableTrafficExchange());
                    if (exchange.url == null) {
                        exchange.method = request.method();
                        exchange.url = request.url();
                        exchange.resourceType = request.resourceType();
                    }
                    Response response = request.response();
                    if (response != null) {
                        exchange.status = response.status();
                        exchange.responseHeadersPreview = redactHeaders(response.headers());
                        String contentType = response.headers().getOrDefault("content-type", "");
                        if (shouldReadResponseBody(exchange, contentType)) {
                            String responseText = response.text();
                            exchange.responseBodyPreview = redactBody(responseText);
                            exchange.responseBodyChars = responseText != null ? responseText.length() : 0;
                            exchange.responseContentType = contentType;
                        } else {
                            exchange.responseContentType = contentType;
                        }
                    }
                    exchange.completedAtMs = System.currentTimeMillis();
                    exchange.score = score(exchange);
                } catch (Exception e) {
                    log.debug("Browser network response capture failed: {}", e.getMessage());
                }
            };
            page.onRequest(requestHandler);
            page.onRequestFinished(requestFinishedHandler);
        }

        private List<TrafficExchange> stop() {
            if (started) {
                if (requestHandler != null) {
                    try { page.offRequest(requestHandler); } catch (Exception ignored) {}
                }
                if (requestFinishedHandler != null) {
                    try { page.offRequestFinished(requestFinishedHandler); } catch (Exception ignored) {}
                }
                started = false;
            }
            return exchanges.values().stream()
                    .map(exchange -> {
                        exchange.score = score(exchange);
                        return exchange.toTrafficExchange();
                    })
                    .filter(exchange -> exchange.score() > 0)
                    .sorted(Comparator.comparingInt(TrafficExchange::score).reversed())
                    .limit(MAX_CAPTURED_EXCHANGES)
                    .toList();
        }

        private boolean shouldReadResponseBody(MutableTrafficExchange exchange, String contentType) {
            String normalizedType = contentType != null ? contentType.toLowerCase() : "";
            if (normalizedType.contains("event-stream")) {
                return false;
            }
            return exchange.promptInRequest
                    || exchange.score >= 3
                    || normalizedType.contains("json")
                    || normalizedType.contains("text/")
                    || normalizedType.contains("ndjson");
        }

        private boolean containsSubmittedMessage(String text) {
            return text != null && submittedMessage != null && !submittedMessage.isBlank()
                    && text.contains(submittedMessage);
        }

        private int score(MutableTrafficExchange exchange) {
            int score = 0;
            String method = safeLower(exchange.method);
            String url = safeLower(exchange.url);
            String resourceType = safeLower(exchange.resourceType);
            String contentType = safeLower(exchange.responseContentType);

            if ("post".equals(method)) score += 2;
            if (exchange.promptInRequest) score += 5;
            if (resourceType.equals("xhr") || resourceType.equals("fetch")
                    || resourceType.equals("eventsource")) score += 2;
            if (url.matches(".*(chat|conversation|completion|message|stream|agent|ask).*")) score += 2;
            if (contentType.contains("json") || contentType.contains("event-stream")
                    || contentType.contains("text/")) score += 1;
            if (url.matches(".*(\\.png|\\.jpg|\\.jpeg|\\.gif|\\.svg|\\.css|\\.js|\\.woff|\\.ico)(\\?.*)?$")) score -= 4;
            if (url.matches(".*(log|track|metric|analytics|beacon|sentry|slardar|collect).*")) score -= 2;
            return Math.max(score, 0);
        }

        private String redactHeaders(Map<String, String> headers) {
            if (headers == null || headers.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            headers.forEach((name, value) -> {
                if (builder.length() > MAX_HEADER_PREVIEW_CHARS) {
                    return;
                }
                builder.append(name).append(": ");
                if (isSensitiveHeader(name)) {
                    builder.append("[REDACTED]");
                } else {
                    builder.append(preview(value, 160));
                }
                builder.append("\n");
            });
            return preview(builder.toString(), MAX_HEADER_PREVIEW_CHARS);
        }

        private String formatHeaders(Map<String, String> headers) {
            if (headers == null || headers.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            headers.forEach((name, value) ->
                    builder.append(name).append(": ")
                            .append(value != null ? value : "")
                            .append("\n"));
            return builder.toString();
        }

        private boolean isSensitiveHeader(String name) {
            if (name == null) return false;
            String lower = name.toLowerCase();
            return lower.contains("authorization")
                    || lower.contains("cookie")
                    || lower.contains("token")
                    || lower.contains("csrf")
                    || lower.contains("secret")
                    || lower.contains("signature");
        }

        private void rememberReplayHeaders(String url, Map<String, String> headers) {
            if (sessionState == null || headers == null || headers.isEmpty()) {
                return;
            }
            Map<String, String> replayHeaders = new HashMap<>();
            headers.forEach((name, value) -> {
                if (isForwardableReplayHeader(name) && value != null && !value.isBlank()) {
                    replayHeaders.put(name, value);
                }
            });
            if (replayHeaders.isEmpty()) {
                return;
            }
            try {
                sessionState.replayHeadersByOrigin.put(originKey(url), replayHeaders);
                log.debug("Stored {} in-memory replay header(s) for {}",
                        replayHeaders.size(), originKey(url));
            } catch (Exception e) {
                log.debug("Failed to store dynamic replay headers: {}", e.getMessage());
            }
        }

        private boolean isForwardableReplayHeader(String name) {
            if (name == null) {
                return false;
            }
            String lower = name.toLowerCase();
            return !lower.equals("host")
                    && !lower.equals("content-length")
                    && !lower.equals("cookie")
                    && !lower.equals("set-cookie")
                    && !lower.equals("connection")
                    && !lower.equals("keep-alive")
                    && !lower.equals("proxy-authenticate")
                    && !lower.equals("proxy-authorization")
                    && !lower.equals("te")
                    && !lower.equals("trailer")
                    && !lower.equals("transfer-encoding")
                    && !lower.equals("upgrade")
                    && !lower.equals("accept-encoding");
        }

        private String redactBody(String body) {
            if (body == null || body.isBlank()) {
                return "";
            }
            String redacted = body
                    .replaceAll("(?i)(\"(?:access_)?token\"\\s*:\\s*\")([^\"]+)(\")", "$1[REDACTED]$3")
                    .replaceAll("(?i)(\"authorization\"\\s*:\\s*\")([^\"]+)(\")", "$1[REDACTED]$3")
                    .replaceAll("(?i)(\"cookie\"\\s*:\\s*\")([^\"]+)(\")", "$1[REDACTED]$3")
                    .replaceAll("(?i)(\"csrf[^\"]*\"\\s*:\\s*\")([^\"]+)(\")", "$1[REDACTED]$3")
                    .replaceAll("(?i)((?:access_)?token=)[^&\\s]+", "$1[REDACTED]")
                    .replaceAll("(?i)(authorization=)[^&\\s]+", "$1[REDACTED]")
                    .replaceAll("(?i)(cookie=)[^&\\s]+", "$1[REDACTED]")
                    .replaceAll("(?i)(csrf[^=]*=)[^&\\s]+", "$1[REDACTED]")
                    .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{12,}", "$1[REDACTED]");
            return preview(redacted, MAX_BODY_PREVIEW_CHARS);
        }

        private String safeLower(String value) {
            return value != null ? value.toLowerCase() : "";
        }
    }

    private static class MutableTrafficExchange {
        String method;
        String url;
        String resourceType;
        String requestHeadersRaw;
        String requestHeadersPreview;
        String requestBodyRaw;
        String requestBodyPreview;
        int requestBodyChars;
        boolean promptInRequest;
        Integer status;
        String responseHeadersPreview;
        String responseBodyPreview;
        int responseBodyChars;
        String responseContentType;
        long startedAtMs;
        long completedAtMs;
        int score;

        TrafficExchange toTrafficExchange() {
            return new TrafficExchange(
                    method,
                    url,
                    resourceType,
                    requestHeadersRaw,
                    requestHeadersPreview,
                    requestBodyRaw,
                    requestBodyPreview,
                    requestBodyChars,
                    promptInRequest,
                    status,
                    responseHeadersPreview,
                    responseBodyPreview,
                    responseBodyChars,
                    responseContentType,
                    startedAtMs,
                    completedAtMs,
                    score);
        }
    }

    public record BrowserMessageResult(String responseText, List<TrafficExchange> trafficExchanges) {}

    public record TrafficExchange(
            String method,
            String url,
            String resourceType,
            String requestHeadersRaw,
            String requestHeadersPreview,
            String requestBodyRaw,
            String requestBodyPreview,
            int requestBodyChars,
            boolean promptInRequest,
            Integer status,
            String responseHeadersPreview,
            String responseBodyPreview,
            int responseBodyChars,
            String responseContentType,
            long startedAtMs,
            long completedAtMs,
            int score) {

        public String urlPreview() {
            if (url == null || url.length() <= 160) {
                return url;
            }
            return url.substring(0, 160) + "...";
        }
    }
}
