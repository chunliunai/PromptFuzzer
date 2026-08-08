package com.promptfuzzer.recon.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptfuzzer.config.BrowserTargetConfig;
import com.promptfuzzer.dto.CreateReconTaskRequest;
import com.promptfuzzer.entity.ReconApplicationSurface;
import com.promptfuzzer.entity.ReconResult;
import com.promptfuzzer.entity.ReconTask;
import com.promptfuzzer.entity.ReconTrace;
import com.promptfuzzer.repository.ReconApplicationSurfaceRepository;
import com.promptfuzzer.repository.ReconResultRepository;
import com.promptfuzzer.repository.ReconTaskRepository;
import com.promptfuzzer.repository.ReconTraceRepository;
import com.promptfuzzer.service.BrowserService;
import com.promptfuzzer.service.HttpRequestService;
import com.promptfuzzer.service.JudgeService;
import com.promptfuzzer.service.ReconPromptService;
import com.promptfuzzer.service.ReconSurfaceQualityService;
import com.promptfuzzer.service.ReconTraceRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconWorkflowEngine {

    private final ReconTaskRepository taskRepository;
    private final ReconResultRepository resultRepository;
    private final ReconTraceRepository traceRepository;
    private final ReconApplicationSurfaceRepository surfaceRepository;
    private final ReconTraceRecorder traceRecorder;
    private final ReconPromptService promptService;
    private final ReconSurfaceQualityService surfaceQualityService;
    private final BrowserService browserService;
    private final HttpRequestService httpRequestService;
    private final JudgeService judgeService;
    private final ObjectMapper objectMapper;

    public void execute(Long reconTaskId) {
        ReconTask task = taskRepository.findById(reconTaskId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "ReconTask not found: " + reconTaskId));
        task.setStatus(ReconTask.Status.RUNNING);
        task.setErrorMessage(null);
        taskRepository.save(task);

        Long browserSessionId = -reconTaskId;
        boolean browserOpened = false;
        try {
            CreateReconTaskRequest.ReconExecutionConfig config =
                    readExecutionConfig(task.getReconConfig());
            ExecutionOutput output;
            if (task.getScanMode() == ReconTask.ScanMode.BROWSER) {
                BrowserTargetConfig browserConfig =
                        objectMapper.readValue(
                                task.getTargetConfig(), BrowserTargetConfig.class);
                browserService.openSession(browserSessionId, browserConfig);
                browserOpened = true;
                output = executeBrowser(task, config, browserSessionId);
            } else {
                output = executeHttp(task, config);
            }

            List<ReconTrace> traces =
                    traceRepository.findByReconTaskIdOrderByIdAsc(task.getId());
            String traceJson =
                    objectMapper.writeValueAsString(toPromptTraceRecords(traces));

            long evidenceStartedAt = System.currentTimeMillis();
            JsonNode evidenceSummary = promptService.summarizeEvidence(
                    task.getReconContext(),
                    task.getExternalIntelligence(),
                    output.targetDescription(),
                    output.coverageMatrix().toString(),
                    traceJson);
            traceRecorder.success(
                    task.getId(),
                    ReconTrace.ActionType.EVIDENCE_SUMMARY,
                    output.targetChatIndex(),
                    output.turnIndex() + 1,
                    null,
                    evidenceSummary.toString(),
                    evidenceSummary.toString(),
                    "整理原子能力事实和证据等级，不直接生成应用面",
                    ReconTrace.EvidenceType.OBSERVED,
                    evidenceStartedAt);

            long synthesisStartedAt = System.currentTimeMillis();
            JsonNode synthesis = promptService.synthesizeApplicationSurfaces(
                    evidenceSummary, output.coverageMatrix().toString());
            traceRecorder.success(
                    task.getId(),
                    ReconTrace.ActionType.APPLICATION_SURFACE_SYNTHESIS,
                    output.targetChatIndex(),
                    output.turnIndex() + 2,
                    null,
                    synthesis.toString(),
                    synthesis.toString(),
                    "将原子能力统一聚合为父子应用面",
                    ReconTrace.EvidenceType.OBSERVED,
                    synthesisStartedAt);

            long qualityStartedAt = System.currentTimeMillis();
            ReconSurfaceQualityService.QualityResult quality =
                    surfaceQualityService.validateAndNormalize(
                            synthesis, evidenceSummary, traces);
            String qualityJson =
                    objectMapper.writeValueAsString(quality.issues());
            traceRecorder.success(
                    task.getId(),
                    ReconTrace.ActionType.SURFACE_QUALITY_GATE,
                    output.targetChatIndex(),
                    output.turnIndex() + 3,
                    null,
                    qualityJson,
                    qualityJson,
                    "校验应用面层级、事实映射、结构化产物和证据一致性",
                    ReconTrace.EvidenceType.OBSERVED,
                    qualityStartedAt);

            ReconResult result = saveResult(
                    task,
                    output.openDiscovery(),
                    output.coveragePlan(),
                    output.coverageMatrix(),
                    evidenceSummary,
                    quality.applicationSurfaces(),
                    quality.issues());
            saveApplicationSurfaces(result, quality.applicationSurfaces());

            task.setStatus(ReconTask.Status.COMPLETED);
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
        } catch (Exception e) {
            log.error("ReconTask {} failed", reconTaskId, e);
            failTask(task, e.getMessage());
        } finally {
            if (browserOpened) {
                try {
                    browserService.closeSession(browserSessionId);
                } catch (Exception e) {
                    log.warn("ReconTask {} browser cleanup failed: {}",
                            reconTaskId, e.getMessage());
                }
            }
        }
    }

    private ExecutionOutput executeBrowser(
            ReconTask task,
            CreateReconTaskRequest.ReconExecutionConfig config,
            Long browserSessionId) throws Exception {
        WorkflowState state = new WorkflowState();
        long readStartedAt = System.currentTimeMillis();
        String pageContent = browserService.readPage(browserSessionId, null);
        traceRecorder.success(
                task.getId(),
                ReconTrace.ActionType.READ_TARGET,
                state.targetChatIndex,
                ++state.turnIndex,
                null,
                pageContent,
                pageContent,
                "读取目标页面可见信息",
                ReconTrace.EvidenceType.EXTERNAL_EVIDENCE,
                readStartedAt);

        JsonNode openDiscovery = executeBrowserOpenDiscovery(
                task, config, pageContent, browserSessionId, state);
        JsonNode coveragePlan = planCoverage(
                task, config, pageContent, openDiscovery, state);
        ArrayNode discoveryRecords = objectMapper.createArrayNode();
        addOpenDiscoveryRecord(discoveryRecords, openDiscovery);
        List<String> discoveryMessages = promptService.discoveryMessages(
                coveragePlan,
                normalize(config.getMaxDiscoveryMessages(), 4, 1, 6));
        if (discoveryMessages.isEmpty()) {
            throw new IllegalStateException(
                    "Coverage plan returned no discovery messages");
        }
        for (String message : discoveryMessages) {
            long startedAt = System.currentTimeMillis();
            state.turnIndex++;
            try {
                String response =
                        browserService.sendMessage(browserSessionId, message);
                requireResponse(
                        response, "Capability discovery returned empty response");
                ReconTrace trace = traceRecorder.success(
                        task.getId(),
                        ReconTrace.ActionType.CAPABILITY_DISCOVERY,
                        state.targetChatIndex,
                        state.turnIndex,
                        message,
                        response,
                        response,
                        "按能力域发现目标用途、工具和资源边界",
                        ReconTrace.EvidenceType.AGENT_CLAIM,
                        startedAt);
                addInteraction(
                        discoveryRecords, message, response,
                        "DOMAIN_DISCOVERY", trace);
            } catch (Exception e) {
                recordDiscoveryError(
                        task,
                        ReconTrace.ActionType.CAPABILITY_DISCOVERY,
                        state.targetChatIndex,
                        state.turnIndex,
                        message, startedAt, e);
                if (browserService.newChat(browserSessionId)) {
                    state.targetChatIndex++;
                }
            }
        }

        JsonNode gapAnalysis = analyzeGaps(
                task, config, coveragePlan, discoveryRecords, state);
        for (String message : promptService.gapFollowupMessages(
                gapAnalysis,
                normalize(config.getMaxGapFollowups(), 3, 0, 4))) {
            long startedAt = System.currentTimeMillis();
            state.turnIndex++;
            try {
                String response =
                        browserService.sendMessage(browserSessionId, message);
                requireResponse(
                        response, "Coverage follow-up returned empty response");
                ReconTrace trace = traceRecorder.success(
                        task.getId(),
                        ReconTrace.ActionType.GAP_FOLLOWUP,
                        state.targetChatIndex,
                        state.turnIndex,
                        message,
                        response,
                        response,
                        "补探覆盖矩阵中的未知能力域",
                        ReconTrace.EvidenceType.AGENT_CLAIM,
                        startedAt);
                addInteraction(
                        discoveryRecords, message, response,
                        "GAP_FOLLOWUP", trace);
            } catch (Exception e) {
                recordDiscoveryError(
                        task,
                        ReconTrace.ActionType.GAP_FOLLOWUP,
                        state.targetChatIndex,
                        state.turnIndex,
                        message, startedAt, e);
                if (browserService.newChat(browserSessionId)) {
                    state.targetChatIndex++;
                }
            }
        }

        executeBrowserVerifications(
                task,
                config,
                pageContent,
                coveragePlan,
                gapAnalysis,
                discoveryRecords,
                browserSessionId,
                state);
        return new ExecutionOutput(
                pageContent,
                state.targetChatIndex,
                state.turnIndex,
                openDiscovery,
                coveragePlan,
                gapAnalysis.path("coverageMatrix"));
    }

    private ExecutionOutput executeHttp(
            ReconTask task,
            CreateReconTaskRequest.ReconExecutionConfig config) throws Exception {
        WorkflowState state = new WorkflowState();
        String targetDescription = describeHttpTarget(task.getRawRequestTemplate());
        long readStartedAt = System.currentTimeMillis();
        traceRecorder.success(
                task.getId(),
                ReconTrace.ActionType.READ_TARGET,
                state.targetChatIndex,
                ++state.turnIndex,
                null,
                null,
                targetDescription,
                "读取 HTTP 请求目标元数据（不包含认证头和值）",
                ReconTrace.EvidenceType.EXTERNAL_EVIDENCE,
                readStartedAt);

        JsonNode openDiscovery = executeHttpOpenDiscovery(
                task, config, targetDescription, state);
        JsonNode coveragePlan = planCoverage(
                task, config, targetDescription, openDiscovery, state);
        ArrayNode discoveryRecords = objectMapper.createArrayNode();
        addOpenDiscoveryRecord(discoveryRecords, openDiscovery);
        List<String> discoveryMessages = promptService.discoveryMessages(
                coveragePlan,
                normalize(config.getMaxDiscoveryMessages(), 4, 1, 6));
        if (discoveryMessages.isEmpty()) {
            throw new IllegalStateException(
                    "Coverage plan returned no discovery messages");
        }
        for (String message : discoveryMessages) {
            long startedAt = System.currentTimeMillis();
            state.turnIndex++;
            try {
                HttpTargetResponse response = sendHttp(
                        task,
                        message,
                        "Capability discovery returned empty response",
                        startedAt);
                ReconTrace trace = traceRecorder.success(
                        task.getId(),
                        ReconTrace.ActionType.CAPABILITY_DISCOVERY,
                        state.targetChatIndex,
                        state.turnIndex,
                        message,
                        response.rawResponse(),
                        response.extractedText(),
                        "按能力域发现目标用途、工具和资源边界",
                        ReconTrace.EvidenceType.AGENT_CLAIM,
                        response.startedAt());
                addInteraction(
                        discoveryRecords, message, response.extractedText(),
                        "DOMAIN_DISCOVERY", trace);
            } catch (Exception e) {
                recordDiscoveryError(
                        task,
                        ReconTrace.ActionType.CAPABILITY_DISCOVERY,
                        state.targetChatIndex,
                        state.turnIndex,
                        message, startedAt, e);
            }
        }

        JsonNode gapAnalysis = analyzeGaps(
                task, config, coveragePlan, discoveryRecords, state);
        for (String message : promptService.gapFollowupMessages(
                gapAnalysis,
                normalize(config.getMaxGapFollowups(), 3, 0, 4))) {
            long startedAt = System.currentTimeMillis();
            state.turnIndex++;
            try {
                HttpTargetResponse response = sendHttp(
                        task,
                        message,
                        "Coverage follow-up returned empty response",
                        startedAt);
                ReconTrace trace = traceRecorder.success(
                        task.getId(),
                        ReconTrace.ActionType.GAP_FOLLOWUP,
                        state.targetChatIndex,
                        state.turnIndex,
                        message,
                        response.rawResponse(),
                        response.extractedText(),
                        "补探覆盖矩阵中的未知能力域",
                        ReconTrace.EvidenceType.AGENT_CLAIM,
                        response.startedAt());
                addInteraction(
                        discoveryRecords, message, response.extractedText(),
                        "GAP_FOLLOWUP", trace);
            } catch (Exception e) {
                recordDiscoveryError(
                        task,
                        ReconTrace.ActionType.GAP_FOLLOWUP,
                        state.targetChatIndex,
                        state.turnIndex,
                        message, startedAt, e);
            }
        }

        executeHttpVerifications(
                task,
                config,
                targetDescription,
                coveragePlan,
                gapAnalysis,
                discoveryRecords,
                state);
        return new ExecutionOutput(
                targetDescription,
                state.targetChatIndex,
                state.turnIndex,
                openDiscovery,
                coveragePlan,
                gapAnalysis.path("coverageMatrix"));
    }

    private JsonNode executeBrowserOpenDiscovery(
            ReconTask task,
            CreateReconTaskRequest.ReconExecutionConfig config,
            String targetDescription,
            Long browserSessionId,
            WorkflowState state) {
        ObjectNode record = objectMapper.createObjectNode();
        if (!isOpenDiscoveryEnabled(config)) {
            record.put("status", "SKIPPED");
            return record;
        }

        long startedAt = System.currentTimeMillis();
        String message = null;
        state.turnIndex++;
        try {
            message = promptService.generateOpenCapabilityDiscoveryMessage(
                    task.getReconContext(), targetDescription);
            String response =
                    browserService.sendMessage(browserSessionId, message);
            requireResponse(
                    response, "Open capability discovery returned empty response");
            ReconTrace trace = traceRecorder.success(
                    task.getId(),
                    ReconTrace.ActionType.OPEN_CAPABILITY_DISCOVERY,
                    state.targetChatIndex,
                    state.turnIndex,
                    message,
                    response,
                    response,
                    "开放询问目标整体业务、工具、输入输出和其他能力",
                    ReconTrace.EvidenceType.AGENT_CLAIM,
                    startedAt);
            fillOpenDiscoveryRecord(record, message, response, trace);
        } catch (Exception e) {
            traceRecorder.error(
                    task.getId(),
                    ReconTrace.ActionType.OPEN_CAPABILITY_DISCOVERY,
                    state.targetChatIndex,
                    state.turnIndex,
                    message,
                    e,
                    startedAt);
            record.put("status", "ERROR");
            record.put("message", message != null ? message : "");
            record.put("error", e.getMessage() != null ? e.getMessage() : "");
            log.warn("ReconTask {} open capability discovery failed and "
                    + "will continue: {}", task.getId(), e.getMessage());
            if (browserService.newChat(browserSessionId)) {
                state.targetChatIndex++;
            }
        }
        return record;
    }

    private JsonNode executeHttpOpenDiscovery(
            ReconTask task,
            CreateReconTaskRequest.ReconExecutionConfig config,
            String targetDescription,
            WorkflowState state) {
        ObjectNode record = objectMapper.createObjectNode();
        if (!isOpenDiscoveryEnabled(config)) {
            record.put("status", "SKIPPED");
            return record;
        }

        long startedAt = System.currentTimeMillis();
        String message = null;
        state.turnIndex++;
        try {
            message = promptService.generateOpenCapabilityDiscoveryMessage(
                    task.getReconContext(), targetDescription);
            HttpTargetResponse response = sendHttp(
                    task,
                    message,
                    "Open capability discovery returned empty response",
                    startedAt);
            ReconTrace trace = traceRecorder.success(
                    task.getId(),
                    ReconTrace.ActionType.OPEN_CAPABILITY_DISCOVERY,
                    state.targetChatIndex,
                    state.turnIndex,
                    message,
                    response.rawResponse(),
                    response.extractedText(),
                    "开放询问目标整体业务、工具、输入输出和其他能力",
                    ReconTrace.EvidenceType.AGENT_CLAIM,
                    response.startedAt());
            fillOpenDiscoveryRecord(
                    record, message, response.extractedText(), trace);
        } catch (Exception e) {
            traceRecorder.error(
                    task.getId(),
                    ReconTrace.ActionType.OPEN_CAPABILITY_DISCOVERY,
                    state.targetChatIndex,
                    state.turnIndex,
                    message,
                    e,
                    startedAt);
            record.put("status", "ERROR");
            record.put("message", message != null ? message : "");
            record.put("error", e.getMessage() != null ? e.getMessage() : "");
            log.warn("ReconTask {} open capability discovery failed and "
                    + "will continue: {}", task.getId(), e.getMessage());
        }
        return record;
    }

    private boolean isOpenDiscoveryEnabled(
            CreateReconTaskRequest.ReconExecutionConfig config) {
        return !Boolean.FALSE.equals(config.getOpenDiscoveryEnabled())
                && normalize(config.getMaxOpenDiscoveryMessages(), 1, 0, 1) > 0;
    }

    private void fillOpenDiscoveryRecord(
            ObjectNode record,
            String message,
            String response,
            ReconTrace trace) {
        record.put("status", "SUCCESS");
        record.put("message", message);
        record.put("response", response);
        if (trace != null && trace.getId() != null) {
            record.put("traceId", trace.getId());
        }
    }

    private void addOpenDiscoveryRecord(
            ArrayNode discoveryRecords,
            JsonNode openDiscovery) {
        if (!"SUCCESS".equals(openDiscovery.path("status").asText())) {
            return;
        }
        ObjectNode record = discoveryRecords.addObject();
        record.put("phase", "OPEN_CAPABILITY_DISCOVERY");
        record.put("message", openDiscovery.path("message").asText(""));
        record.put("response", openDiscovery.path("response").asText(""));
        if (openDiscovery.has("traceId")) {
            record.set("sourceTraceId", openDiscovery.path("traceId"));
        }
    }

    private JsonNode planCoverage(
            ReconTask task,
            CreateReconTaskRequest.ReconExecutionConfig config,
            String targetDescription,
            JsonNode openDiscovery,
            WorkflowState state) throws Exception {
        long startedAt = System.currentTimeMillis();
        JsonNode plan = promptService.planCoverage(
                task.getReconContext(),
                task.getExternalIntelligence(),
                targetDescription,
                openDiscovery.toString(),
                normalize(config.getMaxDiscoveryMessages(), 4, 1, 6));
        traceRecorder.success(
                task.getId(),
                ReconTrace.ActionType.COVERAGE_PLAN,
                state.targetChatIndex,
                ++state.turnIndex,
                null,
                plan.toString(),
                plan.toString(),
                "建立固定能力域矩阵和分类发现计划",
                ReconTrace.EvidenceType.EXTERNAL_EVIDENCE,
                startedAt);
        return plan;
    }

    private JsonNode analyzeGaps(
            ReconTask task,
            CreateReconTaskRequest.ReconExecutionConfig config,
            JsonNode coveragePlan,
            ArrayNode discoveryRecords,
            WorkflowState state) throws Exception {
        long startedAt = System.currentTimeMillis();
        JsonNode gapAnalysis = promptService.analyzeCoverageGaps(
                task.getReconContext(),
                coveragePlan,
                discoveryRecords.toString(),
                normalize(config.getMaxGapFollowups(), 3, 0, 4));
        traceRecorder.success(
                task.getId(),
                ReconTrace.ActionType.COVERAGE_GAP_ANALYSIS,
                state.targetChatIndex,
                ++state.turnIndex,
                null,
                gapAnalysis.toString(),
                gapAnalysis.toString(),
                "识别 UNKNOWN 能力域并生成补探请求",
                ReconTrace.EvidenceType.OBSERVED,
                startedAt);
        return gapAnalysis;
    }

    private void executeBrowserVerifications(
            ReconTask task,
            CreateReconTaskRequest.ReconExecutionConfig config,
            String targetDescription,
            JsonNode coveragePlan,
            JsonNode gapAnalysis,
            ArrayNode discoveryRecords,
            Long browserSessionId,
            WorkflowState state) throws Exception {
        for (String message : verificationMessages(
                task,
                config,
                targetDescription,
                coveragePlan,
                gapAnalysis,
                discoveryRecords)) {
            if (Boolean.TRUE.equals(config.getCleanBetweenVerifications())
                    && browserService.newChat(browserSessionId)) {
                state.targetChatIndex++;
            }
            long startedAt = System.currentTimeMillis();
            state.turnIndex++;
            try {
                String response =
                        browserService.sendMessage(browserSessionId, message);
                requireResponse(
                        response, "Capability verification returned empty response");
                traceRecorder.success(
                        task.getId(),
                        ReconTrace.ActionType.CAPABILITY_VERIFY,
                        state.targetChatIndex,
                        state.turnIndex,
                        message,
                        response,
                        response,
                        "执行单一、无害的原子能力验证；非空回复不代表验证成功",
                        ReconTrace.EvidenceType.AGENT_CLAIM,
                        startedAt);
            } catch (Exception e) {
                recordVerificationError(
                        task, state.targetChatIndex, state.turnIndex,
                        message, startedAt, e);
            }
        }
    }

    private void executeHttpVerifications(
            ReconTask task,
            CreateReconTaskRequest.ReconExecutionConfig config,
            String targetDescription,
            JsonNode coveragePlan,
            JsonNode gapAnalysis,
            ArrayNode discoveryRecords,
            WorkflowState state) throws Exception {
        for (String message : verificationMessages(
                task,
                config,
                targetDescription,
                coveragePlan,
                gapAnalysis,
                discoveryRecords)) {
            long startedAt = System.currentTimeMillis();
            state.turnIndex++;
            try {
                HttpTargetResponse response = sendHttp(
                        task,
                        message,
                        "Capability verification returned empty response",
                        startedAt);
                traceRecorder.success(
                        task.getId(),
                        ReconTrace.ActionType.CAPABILITY_VERIFY,
                        state.targetChatIndex,
                        state.turnIndex,
                        message,
                        response.rawResponse(),
                        response.extractedText(),
                        "执行单一、无害的 HTTP 原子能力验证；非空回复不代表验证成功",
                        ReconTrace.EvidenceType.AGENT_CLAIM,
                        response.startedAt());
            } catch (Exception e) {
                recordVerificationError(
                        task, state.targetChatIndex, state.turnIndex,
                        message, startedAt, e);
            }
        }
    }

    private List<String> verificationMessages(
            ReconTask task,
            CreateReconTaskRequest.ReconExecutionConfig config,
            String targetDescription,
            JsonNode coveragePlan,
            JsonNode gapAnalysis,
            ArrayNode discoveryRecords) throws Exception {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.set("coveragePlan", coveragePlan);
        evidence.set("coverageMatrix", gapAnalysis.path("coverageMatrix"));
        evidence.set("discoveryRecords", discoveryRecords);
        return promptService.generateCapabilityVerificationMessages(
                task.getReconContext(),
                targetDescription,
                evidence.toString(),
                normalize(config.getMaxCapabilityVerifications(), 6, 0, 8));
    }

    private ReconResult saveResult(
            ReconTask task,
            JsonNode openDiscovery,
            JsonNode coveragePlan,
            JsonNode coverageMatrix,
            JsonNode summary,
            ArrayNode applicationSurfaces,
            List<String> qualityIssues) {
        ReconResult result = resultRepository.findByReconTaskId(task.getId())
                .orElseGet(ReconResult::new);
        result.setReconTaskId(task.getId());
        result.setTargetFingerprint(
                summary.path("targetFingerprint").asText(""));
        result.setBusinessProfile(json(summary.path("businessProfile")));
        result.setResponseProfile(json(summary.path("responseProfile")));
        result.setCapabilityInventory(json(summary.path("capabilityInventory")));
        result.setOpenDiscovery(json(openDiscovery));
        result.setCoveragePlan(json(coveragePlan));
        result.setCoverageMatrix(json(coverageMatrix));
        result.setCapabilityFacts(json(summary.path("capabilityFacts")));
        result.setToolInventory(json(summary.path("toolInventory")));
        result.setUnresolvedCapabilities(
                json(summary.path("unresolvedCapabilities")));
        result.setSupportingEvidence(json(summary.path("supportingEvidence")));
        result.setUnresolvedQuestions(json(summary.path("unresolvedQuestions")));
        result.setSurfaceQualityIssues(json(qualityIssues));

        ObjectNode current = objectMapper.createObjectNode();
        current.setAll((ObjectNode) summary.deepCopy());
        current.set("openDiscovery", openDiscovery);
        current.set("coveragePlan", coveragePlan);
        current.set("coverageMatrix", coverageMatrix);
        current.set("applicationSurfaces", applicationSurfaces);
        current.set(
                "surfaceQualityIssues",
                objectMapper.valueToTree(qualityIssues));
        result.setTargetIntelligenceMemory(current.toString());
        result.setRawResult(current.toString());
        result.setCurrentResult(current.toString());
        result.setQualityStatus(parseQuality(
                summary.path("qualityStatus").asText("PARTIAL")));
        return resultRepository.save(result);
    }

    private void saveApplicationSurfaces(
            ReconResult result,
            ArrayNode surfacesNode) {
        Map<String, Long> domainIds = new LinkedHashMap<>();
        for (JsonNode node : surfacesNode) {
            if (!"DOMAIN".equals(node.path("surfaceLevel").asText())) {
                continue;
            }
            ReconApplicationSurface surface = buildSurface(result, node);
            ReconApplicationSurface saved = surfaceRepository.save(surface);
            domainIds.put(node.path("surfaceKey").asText(), saved.getId());
        }
        for (JsonNode node : surfacesNode) {
            if (!"CAPABILITY".equals(node.path("surfaceLevel").asText())) {
                continue;
            }
            ReconApplicationSurface surface = buildSurface(result, node);
            surface.setParentSurfaceId(
                    domainIds.get(node.path("parentSurfaceKey").asText()));
            surfaceRepository.save(surface);
        }
    }

    private ReconApplicationSurface buildSurface(
            ReconResult result,
            JsonNode node) {
        String title = node.path("title").asText("").trim();
        ReconApplicationSurface surface = new ReconApplicationSurface();
        surface.setReconResultId(result.getId());
        surface.setCapabilityKey(node.path("surfaceKey").asText(""));
        surface.setSurfaceLevel(parseSurfaceLevel(
                node.path("surfaceLevel").asText("CAPABILITY")));
        surface.setSelectable(node.path("selectable").asBoolean(
                surface.getSurfaceLevel()
                        == ReconApplicationSurface.SurfaceLevel.CAPABILITY));
        surface.setOriginalTitle(title);
        surface.setOriginalDescription(node.path("description").asText(""));
        surface.setCurrentTitle(title);
        surface.setCurrentDescription(node.path("description").asText(""));
        surface.setSurfaceType(node.path("surfaceType").asText("OTHER"));
        surface.setRelatedTool(node.path("relatedTool").asText(""));
        surface.setSupportedActions(json(node.path("supportedActions")));
        surface.setResourceScope(node.path("resourceScope").asText(""));
        surface.setEvidenceSource(parseEvidenceSource(
                node.path("evidenceSource").asText("AGENT_CLAIM")));
        surface.setVerificationStatus(parseVerificationStatus(
                node.path("verificationStatus").asText("UNVERIFIED")));
        surface.setSourceTraceIds(json(node.path("sourceTraceIds")));
        return surface;
    }

    private void addInteraction(
            ArrayNode records,
            String message,
            String response,
            String phase,
            ReconTrace trace) {
        ObjectNode record = records.addObject();
        record.put("phase", phase);
        record.put("message", message);
        record.put("response", response);
        if (trace != null && trace.getId() != null) {
            record.put("sourceTraceId", trace.getId());
        }
    }

    private HttpTargetResponse sendHttp(
            ReconTask task,
            String message,
            String emptyResponseMessage) throws Exception {
        return sendHttp(
                task, message, emptyResponseMessage, System.currentTimeMillis());
    }

    private HttpTargetResponse sendHttp(
            ReconTask task,
            String message,
            String emptyResponseMessage,
            long startedAt) throws Exception {
        String rawResponse =
                httpRequestService.send(task.getRawRequestTemplate(), message);
        requireResponse(rawResponse, emptyResponseMessage);
        JudgeService.JudgeResult extracted = judgeService.extractOnly(rawResponse);
        String extractedText =
                extracted != null ? extracted.getExtractedText() : null;
        requireResponse(extractedText, emptyResponseMessage);
        return new HttpTargetResponse(rawResponse, extractedText, startedAt);
    }

    private void recordVerificationError(
            ReconTask task,
            int targetChatIndex,
            int turnIndex,
            String verificationMessage,
            long startedAt,
            Exception error) {
        traceRecorder.error(
                task.getId(),
                ReconTrace.ActionType.CAPABILITY_VERIFY,
                targetChatIndex,
                turnIndex,
                verificationMessage,
                error,
                startedAt);
        log.warn("ReconTask {} capability verification failed: {}",
                task.getId(), error.getMessage());
    }

    private void recordDiscoveryError(
            ReconTask task,
            ReconTrace.ActionType actionType,
            int targetChatIndex,
            int turnIndex,
            String discoveryMessage,
            long startedAt,
            Exception error) {
        traceRecorder.error(
                task.getId(),
                actionType,
                targetChatIndex,
                turnIndex,
                discoveryMessage,
                error,
                startedAt);
        log.warn("ReconTask {} capability discovery failed and will continue: {}",
                task.getId(), error.getMessage());
    }

    private String describeHttpTarget(String rawRequestTemplate) {
        String[] lines = rawRequestTemplate.split("\\r?\\n");
        String requestLine =
                lines.length > 0 ? lines[0].trim() : "UNKNOWN";
        String host = "unknown";
        for (String line : lines) {
            if (line.regionMatches(true, 0, "Host:", 0, 5)) {
                host = line.substring(5).trim();
                break;
            }
        }
        return "HTTP_TEMPLATE target: " + requestLine + "; Host: " + host
                + ". No browser-visible page content is available.";
    }

    private List<Map<String, Object>> toPromptTraceRecords(
            List<ReconTrace> traces) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (ReconTrace trace : traces) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("traceId", trace.getId());
            record.put("actionType", trace.getActionType());
            record.put("targetChatIndex", trace.getTargetChatIndex());
            record.put("turnIndex", trace.getTurnIndex());
            if (trace.getActionType() == ReconTrace.ActionType.READ_TARGET) {
                record.put("observation", trace.getObservation());
            } else {
                record.put("requestMessage", trace.getRequestMessage());
                record.put("response", trace.getExtractedText());
                record.put("observation", trace.getObservation());
            }
            record.put("evidenceType", trace.getEvidenceType());
            record.put("status", trace.getStatus());
            records.add(record);
        }
        return records;
    }

    private CreateReconTaskRequest.ReconExecutionConfig readExecutionConfig(
            String json) throws Exception {
        if (json == null || json.isBlank()) {
            return new CreateReconTaskRequest.ReconExecutionConfig();
        }
        return objectMapper.readValue(
                json, CreateReconTaskRequest.ReconExecutionConfig.class);
    }

    private int normalize(Integer value, int fallback, int min, int max) {
        return Math.max(min, Math.min(value != null ? value : fallback, max));
    }

    private void requireResponse(String response, String message) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException(message);
        }
    }

    private void failTask(ReconTask task, String errorMessage) {
        task.setStatus(ReconTask.Status.FAILED);
        task.setErrorMessage(errorMessage);
        task.setCompletedAt(LocalDateTime.now());
        taskRepository.save(task);
    }

    private String json(JsonNode node) {
        return node == null || node.isMissingNode() ? "null" : node.toString();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize RECON data", e);
        }
    }

    private ReconResult.QualityStatus parseQuality(String value) {
        try {
            return ReconResult.QualityStatus.valueOf(
                    value.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return ReconResult.QualityStatus.PARTIAL;
        }
    }

    private ReconApplicationSurface.SurfaceLevel parseSurfaceLevel(
            String value) {
        try {
            return ReconApplicationSurface.SurfaceLevel.valueOf(
                    value.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return ReconApplicationSurface.SurfaceLevel.CAPABILITY;
        }
    }

    private ReconApplicationSurface.EvidenceSource parseEvidenceSource(
            String value) {
        try {
            return ReconApplicationSurface.EvidenceSource.valueOf(
                    value.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return ReconApplicationSurface.EvidenceSource.AGENT_CLAIM;
        }
    }

    private ReconApplicationSurface.VerificationStatus parseVerificationStatus(
            String value) {
        try {
            return ReconApplicationSurface.VerificationStatus.valueOf(
                    value.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return ReconApplicationSurface.VerificationStatus.UNVERIFIED;
        }
    }

    private record ExecutionOutput(
            String targetDescription,
            int targetChatIndex,
            int turnIndex,
            JsonNode openDiscovery,
            JsonNode coveragePlan,
            JsonNode coverageMatrix) {
    }

    private record HttpTargetResponse(
            String rawResponse,
            String extractedText,
            long startedAt) {
    }

    private static final class WorkflowState {
        private int targetChatIndex;
        private int turnIndex;
    }
}
