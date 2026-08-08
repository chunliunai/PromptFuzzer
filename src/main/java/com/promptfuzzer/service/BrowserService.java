package com.promptfuzzer.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import com.promptfuzzer.config.BrowserTargetConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
            "button[data-testid*=\"send\" i]:visible"
    );

    private Playwright playwright;
    private volatile CountDownLatch authLatch;

    /** sessionId → { page, config } */
    private final Map<Long, SessionState> sessions = new ConcurrentHashMap<>();

    private static class SessionState {
        Page page;
        BrowserContext context;
        SharedContext sharedContext;
        BrowserTargetConfig config;
        String targetType;
    }

    private static class SharedContext {
        BrowserContext context;
        int refCount = 1;
    }

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
            createForkedSession(sessionId, reusable, config);
            return;
        }

        BrowserContext context = null;
        try {
            Files.createDirectories(AUTH_DIR);
            initPlaywright();

            Path userDataDir = AUTH_DIR.resolve(config.getTargetType() + "-profile");
            Path authFile = AUTH_DIR.resolve(config.getTargetType() + "-auth.json");
            boolean hasLoggedInBefore = Files.exists(authFile);
            boolean manualLoginRequired = isManualLoginRequired(config);

            context = createContext(userDataDir, authFile, hasLoggedInBefore);

            if (manualLoginRequired && !hasLoggedInBefore) {
                // --- First time: pop up browser for manual login ---
                log.info("No auth for '{}', opening browser for manual login...", config.getTargetType());
                Page loginPage = preparePrimaryPage(context);
                navigateToChat(loginPage, config);
                log.info("==============================================");
                log.info("  浏览器已打开，请完成目标站点登录。");
                log.info("  登录完成后，调用: curl -X POST http://localhost:9091/api/browser/auth-ready");
                log.info("==============================================");
                System.out.println("\n>>> 请完成目标站点登录，然后通知后端登录完成 <<<");
                authLatch = new CountDownLatch(1);
                try {
                    authLatch.await(300, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}

                // Save auth state marker
                Files.writeString(authFile, "logged-in");
                log.info("Auth saved to {}", authFile.toAbsolutePath());
            } else {
                log.info("Browser target '{}' starts without manual login wait (required={}, saved={})",
                        config.getTargetType(), manualLoginRequired, hasLoggedInBefore);
            }

            Page page = preparePrimaryPage(context);
            navigateToChat(page, config);
            closeExtraBlankPages(context, page);
            resolveDefaultSelectors(page, config);

            // Verify input selector is visible
            String inputSelector = getSelector(config, "input");
            page.waitForSelector(inputSelector, new Page.WaitForSelectorOptions().setTimeout(10000));
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

    /**
     * Tool: browser_send_message — type message in input, send, wait for AI reply, return text.
     */
    public String sendMessage(Long sessionId, String message) {
        SessionState state = sessions.get(sessionId);
        if (state == null) throw new IllegalStateException("No browser session: " + sessionId);

        BrowserTargetConfig config = state.config;
        Page page = state.page;

        try {
            String inputSelector = getSelector(config, "input");
            String submitSelector = getSelector(config, "submit");
            waitForInputReady(page, config, 10_000);

            // Type into input
            Locator input = page.locator(inputSelector).first();
            input.click(new Locator.ClickOptions().setTimeout(3000));
            input.fill(message);

            String responseSelector = getResponseSelector(config);
            String assistantSnapshot = readLastAssistantMessageText(page, config);

            // Capture the baseline before submission. Some refusals render so fast
            // that a post-submit snapshot already contains the model response.
            String snapshot = page.locator(responseSelector).innerText();
            String bodySnapshot = "body".equals(responseSelector) ? snapshot : page.locator("body").innerText();

            // Send: prefer submit button when enabled, otherwise fall back to Enter.
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

            return responseText;

        } catch (Exception e) {
            log.error("Browser session {} sendMessage failed", sessionId, e);
            return null;
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

    /**
     * Signal that manual login has been completed. Unblocks the waiting agent thread.
     */
    public void signalAuthReady() {
        if (authLatch != null) {
            authLatch.countDown();
            log.info("Auth ready signal received");
        }
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

    private void submitMessage(Page page, String inputSelector, String submitSelector) {
        if (submitSelector != null && !submitSelector.isBlank()) {
            Locator submit = page.locator(submitSelector);
            try {
                if (submit.isVisible(new Locator.IsVisibleOptions().setTimeout(1500)) && submit.isEnabled()) {
                    submit.click(new Locator.ClickOptions().setTimeout(2000));
                    return;
                }
            } catch (Exception e) {
                log.debug("Submit button click path unavailable, falling back to Enter: {}", e.getMessage());
            }
        }

        if (inputSelector != null && !inputSelector.isBlank()) {
            page.locator(inputSelector).first().press("Enter");
        } else {
            page.keyboard().press("Enter");
        }
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

    private void navigateToChat(Page page, BrowserTargetConfig config) {
        int timeoutMs = Math.max(config.getWaitTimeoutMs(), 45_000);
        page.navigate(config.getChatUrl(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(timeoutMs));
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
    private BrowserContext createContext(Path userDataDir, Path authFile, boolean restoreAuth) {
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

        // If we have saved auth, inject it
        if (restoreAuth && Files.exists(authFile)) {
            try {
                String authJson = Files.readString(authFile);
                // Restore is automatic with the persistent context above
                log.info("Using persistent context from {}", userDataDir);
            } catch (Exception ignored) {}
        }

        return context;
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

        String currentBody = page.locator(responseSelector).innerText();
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
        return page.locator(selector).innerText(new Locator.InnerTextOptions().setTimeout(5000));
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
}
