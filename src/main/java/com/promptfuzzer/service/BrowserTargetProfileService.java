package com.promptfuzzer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.config.BrowserTargetConfig;
import com.promptfuzzer.entity.BrowserTargetProfile;
import com.promptfuzzer.repository.BrowserTargetProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BrowserTargetProfileService {

    private static final Path AUTH_DIR = Path.of("browser-auth");

    private final BrowserTargetProfileRepository repository;
    private final ObjectMapper objectMapper;

    public List<BrowserTargetProfile> listProfiles() {
        return repository.findAll();
    }

    public Optional<BrowserTargetProfile> findByTargetType(String targetType) {
        return repository.findByTargetType(normalizeTargetType(targetType));
    }

    @Transactional
    public Optional<BrowserTargetProfile> findByTargetTypeWithDefaults(String targetType) {
        return findByTargetType(targetType)
                .map(this::persistKnownTargetDefaultsIfNeeded);
    }

    public BrowserTargetConfig configFromProfile(String targetType) {
        BrowserTargetProfile profile = findByTargetType(targetType)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Browser target profile not found: " + normalizeTargetType(targetType)));
        BrowserTargetConfig config = new BrowserTargetConfig();
        config.setTargetType(profile.getTargetType());
        config.setChatUrl(profile.getChatUrl());
        config.setSelectors(readMap(profile.getSelectorsJson()));
        config.setLogin(readMap(profile.getLoginJson()));
        config.setLoginCheck(readMap(profile.getLoginCheckJson()));
        if (profile.getWaitTimeoutMs() != null && profile.getWaitTimeoutMs() > 0) {
            config.setWaitTimeoutMs(profile.getWaitTimeoutMs());
        }
        applyKnownTargetDefaults(config);
        persistConfigDefaults(profile, config);
        return config;
    }

    @Transactional
    public BrowserTargetProfile upsertFromConfig(BrowserTargetConfig config, String displayName) {
        if (config == null) {
            throw new IllegalArgumentException("targetConfig is required");
        }
        String targetType = normalizeTargetType(config.getTargetType());
        config.setTargetType(targetType);
        applyKnownTargetDefaults(config);

        BrowserTargetProfile profile = repository.findByTargetType(targetType)
                .orElseGet(BrowserTargetProfile::new);
        profile.setTargetType(targetType);
        if (displayName != null && !displayName.isBlank()) {
            profile.setDisplayName(displayName.trim());
        } else if (profile.getDisplayName() == null || profile.getDisplayName().isBlank()) {
            profile.setDisplayName(targetType);
        }
        String nextChatUrl = config.getChatUrl() != null ? config.getChatUrl().trim() : profile.getChatUrl();
        String nextSelectorsJson = writeMap(config.getSelectors());
        String nextLoginJson = writeMap(config.getLogin());
        String nextLoginCheckJson = writeMap(config.getLoginCheck());
        boolean configChanged = profile.getId() != null
                && isSemanticConfigChanged(profile, config, nextChatUrl);
        profile.setChatUrl(nextChatUrl);
        profile.setSelectorsJson(nextSelectorsJson);
        profile.setLoginJson(nextLoginJson);
        profile.setLoginCheckJson(nextLoginCheckJson);
        profile.setWaitTimeoutMs(config.getWaitTimeoutMs());
        profile.setProfilePath(profilePathForTargetType(targetType).toString());
        if (profile.getAuthState() == null) {
            profile.setAuthState(BrowserTargetProfile.AuthState.UNKNOWN);
        }
        if (configChanged && profile.getAuthState() == BrowserTargetProfile.AuthState.VERIFIED) {
            profile.setAuthState(BrowserTargetProfile.AuthState.EXPIRED);
            profile.setLastFailureReason("Target browser configuration changed; login state must be verified again.");
        }
        return repository.save(profile);
    }

    @Transactional
    public BrowserTargetProfile markWaitingLogin(BrowserTargetConfig config) {
        BrowserTargetProfile profile = upsertFromConfig(config, null);
        profile.setAuthState(BrowserTargetProfile.AuthState.WAITING_LOGIN);
        profile.setLastFailureReason(null);
        return repository.save(profile);
    }

    @Transactional
    public BrowserTargetProfile markVerifying(String targetType) {
        BrowserTargetProfile profile = requireProfile(targetType);
        profile.setAuthState(BrowserTargetProfile.AuthState.VERIFYING);
        profile.setLastFailureReason(null);
        return repository.save(profile);
    }

    @Transactional
    public BrowserTargetProfile markVerified(String targetType) {
        BrowserTargetProfile profile = requireProfile(targetType);
        profile.setAuthState(BrowserTargetProfile.AuthState.VERIFIED);
        profile.setLastVerifiedAt(LocalDateTime.now());
        profile.setLastLoginAt(LocalDateTime.now());
        profile.setLastFailureReason(null);
        return repository.save(profile);
    }

    @Transactional
    public BrowserTargetProfile markExpired(String targetType, String reason) {
        BrowserTargetProfile profile = requireProfile(targetType);
        profile.setAuthState(BrowserTargetProfile.AuthState.EXPIRED);
        profile.setLastFailureReason(reason);
        return repository.save(profile);
    }

    @Transactional
    public BrowserTargetProfile markFailed(String targetType, String reason) {
        BrowserTargetProfile profile = requireProfile(targetType);
        profile.setAuthState(BrowserTargetProfile.AuthState.FAILED);
        profile.setLastFailureReason(reason);
        return repository.save(profile);
    }

    @Transactional
    public BrowserTargetProfile resetAuthState(String targetType) {
        BrowserTargetProfile profile = requireProfile(targetType);
        profile.setAuthState(BrowserTargetProfile.AuthState.UNKNOWN);
        profile.setLastVerifiedAt(null);
        profile.setLastLoginAt(null);
        profile.setLastFailureReason(null);
        return repository.save(profile);
    }

    public void requireVerifiedForExecution(BrowserTargetConfig config) {
        if (!isManualLoginRequired(config)) {
            upsertFromConfig(config, null);
            return;
        }
        BrowserTargetProfile profile = upsertFromConfig(config, null);
        if (profile.getAuthState() != BrowserTargetProfile.AuthState.VERIFIED) {
            throw new IllegalStateException("Target login is not VERIFIED for targetType="
                    + profile.getTargetType() + ". Current state=" + profile.getAuthState()
                    + ". Please start login and complete verification before running tasks.");
        }
    }

    public boolean isManualLoginRequired(BrowserTargetConfig config) {
        return config != null
                && config.getLogin() != null
                && Boolean.parseBoolean(config.getLogin().getOrDefault("required", "false"));
    }

    public Path profilePathForConfig(BrowserTargetConfig config) {
        return profilePathForTargetType(normalizeTargetType(config != null ? config.getTargetType() : null));
    }

    public Path profilePathForTargetType(String targetType) {
        return AUTH_DIR.resolve(safeTargetType(normalizeTargetType(targetType)) + "-profile");
    }

    private BrowserTargetProfile requireProfile(String targetType) {
        return repository.findByTargetType(normalizeTargetType(targetType))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Browser target profile not found: " + normalizeTargetType(targetType)));
    }

    private String normalizeTargetType(String targetType) {
        return targetType == null || targetType.isBlank() ? "default" : targetType.trim();
    }

    private String safeTargetType(String targetType) {
        return targetType.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void applyKnownTargetDefaults(BrowserTargetConfig config) {
        if (config == null || config.getChatUrl() == null) {
            return;
        }
        String lowerUrl = config.getChatUrl().toLowerCase();
        if (lowerUrl.contains("veda-claw.tiktok-row.net/chat")) {
            applyVedaDefaults(config);
            return;
        }
        if (lowerUrl.contains("/support/chat/")
                && lowerUrl.contains("channelid=")
                && lowerUrl.contains("externalid=")) {
            applyAgentDeskDefaults(config);
        }
    }

    private void applyVedaDefaults(BrowserTargetConfig config) {
        Map<String, String> selectors = mutableCopy(config.getSelectors());
        putIfBlankOrLegacy(selectors, "input", ".chat-editor-content[contenteditable=\"true\"]", "#msg");
        putIfBlankOrLegacy(selectors, "submit", ".chat-input-icon-button", "#send");
        putIfBlankOrLegacy(selectors, "responseArea", "body", "#trans");
        putIfBlankOrLegacy(selectors, "assistantMessage", "body", "#trans");
        putIfBlankOrLegacy(selectors, "newChat", "", "#reset");
        if ("button[aria-label=\"New Chat\"]".equals(selectors.get("newChat"))) {
            selectors.put("newChat", "");
        }
        config.setSelectors(selectors);

        Map<String, String> loginCheck = mutableCopy(config.getLoginCheck());
        putIfBlankOrLegacy(loginCheck, "successSelector", ".chat-editor-content[contenteditable=\"true\"]", "#msg");
        config.setLoginCheck(loginCheck);
    }

    private void applyAgentDeskDefaults(BrowserTargetConfig config) {
        Map<String, String> selectors = mutableCopy(config.getSelectors());
        putIfBlank(selectors, "input", "div[contenteditable=\"true\"][translate=\"no\"]");
        putIfBlank(selectors, "submit", "button[aria-label=\"发送\"]");
        putIfBlank(selectors, "responseArea", "[data-slot=\"message-scroller-content\"]");
        putIfBlank(
                selectors,
                "assistantMessage",
                "[data-slot=\"message\"][data-align=\"start\"] [data-slot=\"bubble-content\"]");
        putIfBlank(selectors, "newChat", "");
        config.setSelectors(selectors);

        Map<String, String> loginCheck = mutableCopy(config.getLoginCheck());
        putIfBlank(
                loginCheck,
                "successSelector",
                "div[contenteditable=\"true\"][translate=\"no\"]");
        config.setLoginCheck(loginCheck);
    }

    private BrowserTargetProfile persistKnownTargetDefaultsIfNeeded(BrowserTargetProfile profile) {
        if (profile == null) {
            return null;
        }
        BrowserTargetConfig config = new BrowserTargetConfig();
        config.setTargetType(profile.getTargetType());
        config.setChatUrl(profile.getChatUrl());
        config.setSelectors(readMap(profile.getSelectorsJson()));
        config.setLogin(readMap(profile.getLoginJson()));
        config.setLoginCheck(readMap(profile.getLoginCheckJson()));
        if (profile.getWaitTimeoutMs() != null && profile.getWaitTimeoutMs() > 0) {
            config.setWaitTimeoutMs(profile.getWaitTimeoutMs());
        }
        applyKnownTargetDefaults(config);
        return persistConfigDefaults(profile, config);
    }

    private BrowserTargetProfile persistConfigDefaults(
            BrowserTargetProfile profile,
            BrowserTargetConfig config) {
        String nextSelectorsJson = writeMap(config.getSelectors());
        String nextLoginCheckJson = writeMap(config.getLoginCheck());
        boolean changed = !Objects.equals(profile.getSelectorsJson(), nextSelectorsJson)
                || !Objects.equals(profile.getLoginCheckJson(), nextLoginCheckJson);
        if (!changed) {
            return profile;
        }
        profile.setSelectorsJson(nextSelectorsJson);
        profile.setLoginCheckJson(nextLoginCheckJson);
        return repository.save(profile);
    }

    private boolean isSemanticConfigChanged(
            BrowserTargetProfile profile,
            BrowserTargetConfig nextConfig,
            String nextChatUrl) {
        BrowserTargetConfig storedConfig = new BrowserTargetConfig();
        storedConfig.setTargetType(profile.getTargetType());
        storedConfig.setChatUrl(profile.getChatUrl());
        storedConfig.setSelectors(readMap(profile.getSelectorsJson()));
        storedConfig.setLogin(readMap(profile.getLoginJson()));
        storedConfig.setLoginCheck(readMap(profile.getLoginCheckJson()));
        applyKnownTargetDefaults(storedConfig);

        return !Objects.equals(profile.getChatUrl(), nextChatUrl)
                || !mapsEqual(storedConfig.getSelectors(), nextConfig.getSelectors())
                || !mapsEqual(storedConfig.getLogin(), nextConfig.getLogin())
                || !mapsEqual(storedConfig.getLoginCheck(), nextConfig.getLoginCheck());
    }

    private boolean mapsEqual(Map<String, String> left, Map<String, String> right) {
        Map<String, String> normalizedLeft = left == null ? Map.of() : left;
        Map<String, String> normalizedRight = right == null ? Map.of() : right;
        return Objects.equals(normalizedLeft, normalizedRight);
    }

    private Map<String, String> mutableCopy(Map<String, String> value) {
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    private void putIfBlankOrLegacy(
            Map<String, String> target,
            String key,
            String value,
            String legacyValue) {
        String current = target.get(key);
        if (current == null || current.isBlank() || current.equals(legacyValue)) {
            target.put(key, value);
        }
    }

    private void putIfBlank(
            Map<String, String> target,
            String key,
            String value) {
        String current = target.get(key);
        if (current == null || current.isBlank()) {
            target.put(key, value);
        }
    }

    private String writeMap(Map<String, String> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid browser target config", e);
        }
    }

    private Map<String, String> readMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid stored browser target config", e);
        }
    }
}
