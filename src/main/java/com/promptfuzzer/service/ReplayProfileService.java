package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.config.BrowserTargetConfig;
import com.promptfuzzer.dto.ReplayProfileValidationResponse;
import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.ReplayProfile;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.repository.ReplayProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReplayProfileService {

    private static final int MIN_PROFILE_SCORE = 7;
    private static final int TEXT_MAX_CHARS = 4000;

    private final ReplayProfileRepository replayProfileRepository;
    private final HttpRequestService httpRequestService;
    private final BrowserService browserService;
    private final TargetResponseNormalizerService targetResponseNormalizerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public Optional<ReplayProfile> observeBrowserTraffic(AgentWorkflowContext context,
                                                         AgentWorkflowState state,
                                                         String submittedMessage,
                                                         List<BrowserService.TrafficExchange> exchanges) {
        if (context == null || context.getTask() == null || exchanges == null || exchanges.isEmpty()) {
            return Optional.empty();
        }
        Optional<BrowserService.TrafficExchange> selected = exchanges.stream()
                .filter(exchange -> exchange.promptInRequest()
                        && isReplayProfileCandidate(exchange)
                        && exchange.score() >= MIN_PROFILE_SCORE)
                .max(Comparator.comparingInt(BrowserService.TrafficExchange::score));
        if (selected.isEmpty()) {
            return Optional.empty();
        }

        BrowserService.TrafficExchange exchange = selected.get();
        String fingerprint = endpointFingerprint(exchange.method(), exchange.url());
        if (fingerprint == null) {
            return Optional.empty();
        }

        Task task = context.getTask();
        AgentSession session = context.getSession();
        Integer targetChatIndex = state != null ? state.getTargetChatIndex() : null;
        ReplayProfile profile = targetChatIndex != null
                ? replayProfileRepository.findByTaskIdAndTargetChatIndexOrderByUpdatedAtDesc(task.getId(), targetChatIndex)
                .stream()
                .filter(existing -> Objects.equals(existing.getEndpointFingerprint(), fingerprint))
                .findFirst()
                .orElseGet(ReplayProfile::new)
                : replayProfileRepository
                .findFirstByTaskIdAndEndpointFingerprintOrderByUpdatedAtDesc(task.getId(), fingerprint)
                .orElseGet(ReplayProfile::new);

        profile.setTaskId(task.getId());
        if (session != null) {
            profile.setAgentSessionId(session.getId());
            profile.setSessionIndex(session.getSessionIndex());
        }
        if (state != null) {
            profile.setTurnIndex(state.getTurnIndex());
            profile.setTargetChatIndex(state.getTargetChatIndex());
        }
        profile.setTargetType(resolveTargetType(task));
        profile.setEndpointFingerprint(fingerprint);
        profile.setClassification(classify(exchange));
        profile.setMethod(valueOrDefault(exchange.method(), "POST"));
        profile.setUrl(safeUrl(exchange.url()));
        profile.setRequestTemplate(buildRequestTemplate(exchange, submittedMessage));
        profile.setPayloadFieldPath(inferPayloadFieldPath(requestBodyForReplay(exchange), submittedMessage));
        profile.setDynamicBindings(buildDynamicBindings(exchange));
        profile.setResponseExtractionRule(buildResponseExtractionRule(exchange));
        profile.setEvidenceSummary(buildEvidenceSummary(exchange));
        if (profile.getValidationCount() == null) {
            profile.setValidationCount(0);
        }
        if (profile.getFailureCount() == null) {
            profile.setFailureCount(0);
        }
        if (value(profile.getFailureCount()) == 0) {
            profile.setLastFailureReason(classificationReason(exchange));
        }

        ReplayProfile saved = replayProfileRepository.save(profile);
        log.info("Replay profile observed for task {}: id={} classification={} endpoint={}",
                task.getId(), saved.getId(), saved.getClassification(), saved.getEndpointFingerprint());
        return Optional.of(saved);
    }

    public List<ReplayProfile> listByTaskId(Long taskId) {
        return replayProfileRepository.findByTaskIdOrderByUpdatedAtDesc(taskId);
    }

    public Optional<ReplayProfile> findReplayCandidate(Task task, AgentWorkflowState state) {
        if (task == null || state == null) {
            return Optional.empty();
        }
        if (state.getTargetChatIndex() == null) {
            return selectBestReplayCandidate(replayProfileRepository.findByTaskIdOrderByUpdatedAtDesc(task.getId()));
        }
        List<ReplayProfile> profiles = replayProfileRepository
                .findByTaskIdAndTargetChatIndexOrderByUpdatedAtDesc(
                        task.getId(), state.getTargetChatIndex());
        return selectBestReplayCandidate(profiles);
    }

    @Transactional
    public Optional<ReplayExecutionResult> replayLatestCandidate(Task task,
                                                                AgentWorkflowState state,
                                                                Long browserSessionId,
                                                                String prompt) {
        Optional<ReplayProfile> candidate = findReplayCandidate(task, state);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(replayWithProfile(candidate.get(), state, browserSessionId, prompt));
    }

    @Transactional
    public ReplayProfileValidationResponse validateProfile(Long taskId,
                                                           Long profileId,
                                                           Long browserSessionId,
                                                           String prompt) {
        ReplayProfile profile = replayProfileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("ReplayProfile not found: " + profileId));
        if (!Objects.equals(taskId, profile.getTaskId())) {
            throw new IllegalArgumentException("ReplayProfile " + profileId + " does not belong to task " + taskId);
        }
        String validationPrompt = prompt != null && !prompt.isBlank()
                ? prompt
                : "Reply with exactly: replay validation ok";
        try {
            ReplayRequest request = buildReplayRequest(profile, null, browserSessionId, validationPrompt);
            HttpRequestService.HttpTargetResponse response = httpRequestService.sendDetailed(
                    request.method(), request.url(), request.headers(), request.body());
            TargetResponseNormalizerService.NormalizedTargetResponse normalized =
                    targetResponseNormalizerService.normalize(response.rawResponse(), response.contentType(),
                            "HTTP_REPLAY", true);
            String extractedText = normalized.extractedText();
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300
                    && extractedText != null && !extractedText.isBlank();

            if (success) {
                profile.setValidationCount(value(profile.getValidationCount()) + 1);
                profile.setLastValidatedAt(LocalDateTime.now());
                profile.setLastFailureReason(null);
                profile.setClassification(ReplayProfile.Classification.HTTP_REPLAYABLE);
            } else {
                profile.setFailureCount(value(profile.getFailureCount()) + 1);
                profile.setLastFailureReason(failureReason(response, extractedText));
            }
            ReplayProfile saved = replayProfileRepository.save(profile);
            return ReplayProfileValidationResponse.of(
                    saved, success, response.statusCode(), response.contentType(),
                    response.rawResponse(), extractedText, saved.getLastFailureReason());
        } catch (Exception e) {
            profile.setFailureCount(value(profile.getFailureCount()) + 1);
            profile.setLastFailureReason(truncate(e.getMessage(), 1000));
            ReplayProfile saved = replayProfileRepository.save(profile);
            return ReplayProfileValidationResponse.of(
                    saved, false, 0, null, null, null, saved.getLastFailureReason());
        }
    }

    private ReplayExecutionResult replayWithProfile(ReplayProfile profile,
                                                    AgentWorkflowState state,
                                                    Long browserSessionId,
                                                    String prompt) {
        try {
            ReplayRequest request = buildReplayRequest(profile, state, browserSessionId, prompt);
            HttpRequestService.HttpTargetResponse response = httpRequestService.sendDetailed(
                    request.method(), request.url(), request.headers(), request.body());
            TargetResponseNormalizerService.NormalizedTargetResponse normalized =
                    targetResponseNormalizerService.normalize(response.rawResponse(), response.contentType(),
                            "HTTP_REPLAY", true);
            String extractedText = normalized.extractedText();
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300
                    && extractedText != null && !extractedText.isBlank();
            if (success) {
                profile.setValidationCount(value(profile.getValidationCount()) + 1);
                profile.setLastValidatedAt(LocalDateTime.now());
                profile.setLastFailureReason(null);
                profile.setClassification(ReplayProfile.Classification.HTTP_REPLAYABLE);
            } else {
                profile.setFailureCount(value(profile.getFailureCount()) + 1);
                profile.setLastFailureReason(failureReason(response, extractedText));
            }
            ReplayProfile saved = replayProfileRepository.save(profile);
            return new ReplayExecutionResult(saved, success, response.statusCode(), response.contentType(),
                    response.rawResponse(), extractedText, request.method(), request.url(),
                    request.headers(), request.body(),
                    success ? null : failureType(response, extractedText),
                    success ? null : saved.getLastFailureReason());
        } catch (Exception e) {
            profile.setFailureCount(value(profile.getFailureCount()) + 1);
            String failureType = failureType(e);
            profile.setLastFailureReason(failureType + ": " + truncate(e.getMessage(), 1000));
            ReplayProfile saved = replayProfileRepository.save(profile);
            return new ReplayExecutionResult(saved, false, 0, null, null, null,
                    null, null, null, null, failureType, saved.getLastFailureReason());
        }
    }

    ReplayRequest buildReplayRequest(ReplayProfile profile,
                                     AgentWorkflowState state,
                                     Long browserSessionId,
                                     String prompt) throws Exception {
        JsonNode template = objectMapper.readTree(profile.getRequestTemplate());
        String method = template.path("method").asText(profile.getMethod());
        String url = template.path("url").asText(profile.getUrl());
        String body = template.path("bodyTemplate").asText("");
        body = body.replace("{{PAYLOAD}}", escapeJson(prompt));

        Map<String, String> headers = parseHeadersPreview(
                template.path("headers").asText(template.path("headersPreview").asText("")));
        removeHeaderIgnoreCase(headers, "Host");
        removeHeaderIgnoreCase(headers, "Content-Length");
        removeHeaderIgnoreCase(headers, "Cookie");
        removeHeaderIgnoreCase(headers, "Authorization");
        if (browserSessionId != null) {
            headers.putAll(browserService.dynamicReplayHeadersForUrl(browserSessionId, url));
        }
        removeHeaderIgnoreCase(headers, "Host");
        removeHeaderIgnoreCase(headers, "Content-Length");
        removeHeaderIgnoreCase(headers, "Cookie");
        if (headers.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("Accept"))) {
            headers.put("Accept", "*/*");
        }
        String cookieHeader = browserSessionId != null
                ? browserService.cookieHeaderForUrl(browserSessionId, url) : "";
        if (cookieHeader != null && !cookieHeader.isBlank()) {
            headers.put("Cookie", cookieHeader);
        }
        if (shouldCreateFreshTargetSession(profile, state)) {
            String sessionId = createFreshTargetSession(profile, browserSessionId, url, headers, body);
            body = replaceSessionId(body, sessionId);
        }
        return new ReplayRequest(method, url, headers, body);
    }

    private Optional<ReplayProfile> selectBestReplayCandidate(List<ReplayProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return Optional.empty();
        }
        return profiles.stream()
                .filter(profile -> profile.getClassification() != ReplayProfile.Classification.BROWSER_ONLY)
                .filter(profile -> profile.getRequestTemplate() != null && !profile.getRequestTemplate().isBlank())
                .filter(profile -> profile.getUrl() != null && !profile.getUrl().isBlank())
                .max(Comparator
                        .comparingInt((ReplayProfile profile) ->
                                profile.getClassification() == ReplayProfile.Classification.HTTP_REPLAYABLE ? 1 : 0)
                        .thenComparingInt(profile -> value(profile.getValidationCount())));
    }

    private ReplayProfile.Classification classify(BrowserService.TrafficExchange exchange) {
        String contentType = lower(exchange.responseContentType());
        if (contentType.contains("event-stream")) {
            return ReplayProfile.Classification.UNKNOWN;
        }
        return ReplayProfile.Classification.UNKNOWN;
    }

    private String classificationReason(BrowserService.TrafficExchange exchange) {
        String contentType = lower(exchange.responseContentType());
        if (contentType.contains("event-stream")) {
            return "Captured SSE model endpoint; requires stream parser and control replay before HTTP_REPLAYABLE.";
        }
        return "Captured model-like endpoint; requires control replay before HTTP_REPLAYABLE.";
    }

    private boolean isSuccessStatus(Integer status) {
        return status != null && status >= 200 && status < 300;
    }

    private boolean isReplayProfileCandidate(BrowserService.TrafficExchange exchange) {
        if (exchange == null) {
            return false;
        }
        if (isSuccessStatus(exchange.status())) {
            return true;
        }
        String url = lower(exchange.url());
        String contentType = lower(exchange.responseContentType());
        return exchange.status() == null
                && (url.contains("/stream")
                || url.contains("completion")
                || contentType.contains("event-stream"));
    }

    private String buildRequestTemplate(BrowserService.TrafficExchange exchange, String submittedMessage) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("method", exchange.method());
        template.put("url", safeUrl(exchange.url()));
        template.put("headersPreview", truncate(exchange.requestHeadersPreview(), TEXT_MAX_CHARS));
        template.put("headers", headersForReplay(exchange));
        template.put("bodyTemplate", replacePayload(requestBodyForReplay(exchange), submittedMessage));
        template.put("sensitiveValuesStored", true);
        try {
            return objectMapper.writeValueAsString(template);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildDynamicBindings(BrowserService.TrafficExchange exchange) {
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("authSource", "browser_context_required");
        bindings.put("storesSensitiveValues", true);
        bindings.put("headerRedactions", inferRedactedHeaders(exchange.requestHeadersPreview()));
        bindings.put("requiresControlReplay", true);
        bindings.put("sessionIdFieldPath", inferSessionIdFieldPath(requestBodyForReplay(exchange)));
        bindings.put("sessionCreateUrl", inferSessionCreateUrl(exchange.url()));
        try {
            return objectMapper.writeValueAsString(bindings);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildResponseExtractionRule(BrowserService.TrafficExchange exchange) {
        Map<String, Object> rule = new LinkedHashMap<>();
        String contentType = lower(exchange.responseContentType());
        rule.put("contentType", exchange.responseContentType());
        if (contentType.contains("event-stream")) {
            rule.put("protocol", "SSE");
            rule.put("status", "needs_parser");
        } else if (contentType.contains("json")) {
            rule.put("protocol", "JSON");
            rule.put("status", "needs_field_mapping");
        } else {
            rule.put("protocol", "TEXT");
            rule.put("status", "candidate");
        }
        try {
            return objectMapper.writeValueAsString(rule);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildEvidenceSummary(BrowserService.TrafficExchange exchange) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("score", exchange.score());
        evidence.put("promptInRequest", exchange.promptInRequest());
        evidence.put("resourceType", exchange.resourceType());
        evidence.put("status", exchange.status());
        evidence.put("requestBodyChars", exchange.requestBodyChars());
        evidence.put("responseBodyChars", exchange.responseBodyChars());
        evidence.put("responseContentType", exchange.responseContentType());
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String inferPayloadFieldPath(String body, String submittedMessage) {
        if (body == null || body.isBlank() || submittedMessage == null || submittedMessage.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return findTextPath(root, submittedMessage, "$");
        } catch (Exception ignored) {
            return body.contains(submittedMessage) ? "$" : null;
        }
    }

    private String findTextPath(JsonNode node, String submittedMessage, String path) {
        if (node == null) {
            return null;
        }
        if (node.isTextual() && node.asText().contains(submittedMessage)) {
            return path;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String found = findTextPath(entry.getValue(), submittedMessage,
                        path + "." + entry.getKey());
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                String found = findTextPath(array.get(i), submittedMessage,
                        path + "[" + i + "]");
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String inferSessionIdFieldPath(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return findNamedTextPath(root, "$", Set.of(
                    "session_id", "sessionId", "chat_session_id", "chatSessionId",
                    "conversation_id", "conversationId", "chat_id", "chatId"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String findNamedTextPath(JsonNode node, String path, Set<String> names) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (names.contains(entry.getKey()) && entry.getValue().isTextual()
                        && !entry.getValue().asText().isBlank()) {
                    return path + "." + entry.getKey();
                }
                String found = findNamedTextPath(entry.getValue(), path + "." + entry.getKey(), names);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                String found = findNamedTextPath(array.get(i), path + "[" + i + "]", names);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String replacePayload(String body, String submittedMessage) {
        if (body == null || body.isBlank()) {
            return "";
        }
        if (submittedMessage == null || submittedMessage.isBlank()) {
            return body;
        }
        return body.replace(submittedMessage, "{{PAYLOAD}}");
    }

    private String headersForReplay(BrowserService.TrafficExchange exchange) {
        String raw = exchange.requestHeadersRaw();
        return raw != null && !raw.isBlank() ? raw : exchange.requestHeadersPreview();
    }

    private String requestBodyForReplay(BrowserService.TrafficExchange exchange) {
        String raw = exchange.requestBodyRaw();
        return raw != null && !raw.isBlank() ? raw : exchange.requestBodyPreview();
    }

    private List<String> inferRedactedHeaders(String headersPreview) {
        if (headersPreview == null || headersPreview.isBlank()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String line : headersPreview.split("\\r?\\n")) {
            if (!line.contains("[REDACTED]")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                names.add(line.substring(0, colon).trim());
            }
        }
        return names;
    }

    private Map<String, String> parseHeadersPreview(String headersPreview) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (headersPreview == null || headersPreview.isBlank()) {
            return headers;
        }
        for (String line : headersPreview.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (name.isBlank() || value.isBlank() || value.contains("[REDACTED]")) {
                continue;
            }
            headers.put(name, value);
        }
        return headers;
    }

    private void removeHeaderIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty() || name == null) {
            return;
        }
        headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
    }

    private boolean shouldCreateFreshTargetSession(ReplayProfile profile, AgentWorkflowState state) {
        if (profile == null || state == null || state.getTargetChatIndex() == null) {
            return false;
        }
        if (Objects.equals(profile.getTargetChatIndex(), state.getTargetChatIndex())) {
            return false;
        }
        return profile.getRequestTemplate() != null
                && profile.getRequestTemplate().contains("session_id");
    }

    private String createFreshTargetSession(ReplayProfile profile,
                                            Long browserSessionId,
                                            String streamUrl,
                                            Map<String, String> streamHeaders,
                                            String streamBody) throws Exception {
        String sessionCreateUrl = sessionCreateUrl(profile, streamUrl);
        if (sessionCreateUrl == null) {
            throw new IllegalStateException("No session creation endpoint can be inferred for " + streamUrl);
        }

        Map<String, String> headers = new LinkedHashMap<>(streamHeaders);
        headers.remove("Accept");
        headers.put("Content-Type", "application/json");
        if (browserSessionId != null) {
            headers.putAll(browserService.dynamicReplayHeadersForUrl(browserSessionId, sessionCreateUrl));
        }

        List<String> bodies = sessionCreateBodies(streamUrl, streamBody, headers);
        Exception lastError = null;
        for (String body : bodies) {
            try {
                HttpRequestService.HttpTargetResponse response = httpRequestService.sendDetailed(
                        "POST", sessionCreateUrl, headers, body);
                String sessionId = extractCreatedSessionId(response.body());
                if (response.statusCode() >= 200 && response.statusCode() < 300
                        && sessionId != null && !sessionId.isBlank()) {
                    log.info("Created target HTTP session via {} for replay profile {}", sessionCreateUrl, profile.getId());
                    return sessionId;
                }
                lastError = new IllegalStateException(
                        "session create status=" + response.statusCode()
                                + ", body=" + truncate(response.body(), 300));
            } catch (Exception e) {
                lastError = e;
            }
        }
        throw new IllegalStateException("Target session creation failed: "
                + (lastError != null ? lastError.getMessage() : "no request attempted"), lastError);
    }

    private String sessionCreateUrl(ReplayProfile profile, String streamUrl) {
        try {
            JsonNode bindings = objectMapper.readTree(valueOrDefault(profile.getDynamicBindings(), "{}"));
            String configured = bindings.path("sessionCreateUrl").asText("");
            if (!configured.isBlank()) {
                return configured;
            }
        } catch (Exception ignored) {
        }
        return inferSessionCreateUrl(streamUrl);
    }

    private String inferSessionCreateUrl(String streamUrl) {
        if (streamUrl == null || streamUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(streamUrl);
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                return null;
            }
            String sessionPath = path;
            if (sessionPath.endsWith("/stream")) {
                sessionPath = sessionPath.substring(0, sessionPath.length() - "/stream".length()) + "/session";
            } else if (sessionPath.contains("/chat/")) {
                sessionPath = sessionPath.replaceFirst("/chat/[^/]+$", "/chat/session");
            } else {
                return null;
            }
            return new URI(uri.getScheme(), uri.getAuthority(), sessionPath, null, null).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> sessionCreateBodies(String streamUrl,
                                             String streamBody,
                                             Map<String, String> headers) {
        LinkedHashSet<String> bodies = new LinkedHashSet<>();
        String zoneId = extractQueryParam(streamUrl, "zoneId");
        if ((zoneId == null || zoneId.isBlank()) && headers != null) {
            zoneId = extractQueryParam(headerValue(headers, "Referer"), "zoneId");
        }
        String user = textField(streamBody, "user");
        if (zoneId != null && !zoneId.isBlank()) {
            bodies.add(jsonBody(Map.of("zone_id", zoneId)));
            bodies.add(jsonBody(Map.of("zoneId", zoneId)));
            if (user != null && !user.isBlank()) {
                Map<String, String> withUser = new LinkedHashMap<>();
                withUser.put("zone_id", zoneId);
                withUser.put("user", user);
                bodies.add(jsonBody(withUser));
            }
        }
        bodies.add("{}");
        if (user != null && !user.isBlank()) {
            bodies.add(jsonBody(Map.of("user", user)));
            String modelName = textField(streamBody, "model_name");
            if (modelName != null && !modelName.isBlank()) {
                Map<String, String> withModel = new LinkedHashMap<>();
                withModel.put("user", user);
                withModel.put("model_name", modelName);
                bodies.add(jsonBody(withModel));
            }
        }
        return new ArrayList<>(bodies);
    }

    private String replaceSessionId(String body, String sessionId) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        if (replaceNamedTextValue(root, Set.of(
                "session_id", "sessionId", "chat_session_id", "chatSessionId",
                "conversation_id", "conversationId", "chat_id", "chatId"), sessionId)) {
            return objectMapper.writeValueAsString(root);
        }
        return body;
    }

    private boolean replaceNamedTextValue(JsonNode node, Set<String> names, String newValue) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (names.contains(entry.getKey()) && entry.getValue().isTextual()) {
                    objectNode.put(entry.getKey(), newValue);
                    return true;
                }
                if (replaceNamedTextValue(entry.getValue(), names, newValue)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                if (replaceNamedTextValue(array.get(i), names, newValue)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String extractCreatedSessionId(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String preferred = firstTextValue(root, List.of(
                    "session_id", "sessionId", "chat_session_id", "chatSessionId",
                    "conversation_id", "conversationId"));
            if (preferred != null) {
                return preferred;
            }
            return firstTextValue(root, List.of("id"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstTextValue(JsonNode node, List<String> names) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (names.contains(entry.getKey()) && entry.getValue().isTextual()
                        && !entry.getValue().asText().isBlank()) {
                    return entry.getValue().asText();
                }
            }
            Iterator<Map.Entry<String, JsonNode>> nested = node.fields();
            while (nested.hasNext()) {
                String found = firstTextValue(nested.next().getValue(), names);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String found = firstTextValue(item, names);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String headerValue(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private String textField(String body, String fieldName) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode value = root.path(fieldName);
            return value.isTextual() ? value.asText() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractQueryParam(String url, String name) {
        try {
            String query = URI.create(url).getQuery();
            if (query == null || query.isBlank()) {
                return null;
            }
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                String key = equals >= 0 ? pair.substring(0, equals) : pair;
                if (Objects.equals(key, name)) {
                    return equals >= 0 ? pair.substring(equals + 1) : "";
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String jsonBody(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String failureReason(HttpRequestService.HttpTargetResponse response, String extractedText) {
        return failureType(response, extractedText) + ": HTTP replay returned status="
                + response.statusCode() + ", extractedTextChars=" + length(extractedText);
    }

    private String failureType(HttpRequestService.HttpTargetResponse response, String extractedText) {
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return "AUTH_EXPIRED";
        }
        if (response.statusCode() == 400 || response.statusCode() == 422) {
            return "REQUEST_SHAPE_CHANGED";
        }
        if (response.statusCode() >= 500) {
            return "TARGET_SERVER_ERROR";
        }
        if (response.statusCode() >= 200 && response.statusCode() < 300
                && (extractedText == null || extractedText.isBlank())) {
            return "EMPTY_RESPONSE";
        }
        return "HTTP_REPLAY_FAILED";
    }

    private String failureType(Exception e) {
        if (lower(e.getMessage()).contains("session creation")) {
            return "SESSION_CREATE_FAILED";
        }
        if (e instanceof SocketTimeoutException
                || lower(e.getMessage()).contains("timed out")
                || lower(e.getMessage()).contains("timeout")) {
            return "TIMEOUT";
        }
        if (lower(e.getMessage()).contains("401") || lower(e.getMessage()).contains("403")) {
            return "AUTH_EXPIRED";
        }
        if (lower(e.getMessage()).contains("400") || lower(e.getMessage()).contains("422")) {
            return "REQUEST_SHAPE_CHANGED";
        }
        return "NETWORK_OR_REPLAY_ERROR";
    }

    private String endpointFingerprint(String method, String url) {
        try {
            URI uri = URI.create(url);
            String normalized = lower(method) + " "
                    + lower(uri.getScheme()) + "://"
                    + lower(uri.getHost())
                    + valueOrDefault(uri.getPath(), "/");
            return sha256(normalized);
        } catch (Exception e) {
            return null;
        }
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte b : hash) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private String resolveTargetType(Task task) {
        if (task.getTargetConfig() == null || task.getTargetConfig().isBlank()) {
            return null;
        }
        try {
            BrowserTargetConfig config = objectMapper.readValue(task.getTargetConfig(), BrowserTargetConfig.class);
            return truncate(config.getTargetType(), 160);
        } catch (Exception e) {
            return null;
        }
    }

    private String safeUrl(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            StringBuilder safe = new StringBuilder();
            if (uri.getScheme() != null) {
                safe.append(uri.getScheme()).append("://");
            }
            if (uri.getRawAuthority() != null) {
                safe.append(uri.getRawAuthority());
            }
            safe.append(uri.getRawPath() != null && !uri.getRawPath().isBlank()
                    ? uri.getRawPath() : "/");
            if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
                safe.append("?").append(uri.getRawQuery());
            }
            return safe.toString();
        } catch (Exception e) {
            return truncate(url, TEXT_MAX_CHARS);
        }
    }

    private String lower(String value) {
        return value != null ? value.toLowerCase(Locale.ROOT) : "";
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    private int value(Integer value) {
        return value != null ? value : 0;
    }

    private int length(String value) {
        return value != null ? value.length() : 0;
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars)) + "...";
    }

    record ReplayRequest(String method, String url, Map<String, String> headers, String body) {
    }

    public record ReplayExecutionResult(
            ReplayProfile profile,
            boolean success,
            int statusCode,
            String contentType,
            String rawResponse,
            String extractedText,
              String requestMethod,
              String requestUrl,
              Map<String, String> requestHeaders,
              String requestBody,
            String failureType,
            String failureReason) {
    }
}
