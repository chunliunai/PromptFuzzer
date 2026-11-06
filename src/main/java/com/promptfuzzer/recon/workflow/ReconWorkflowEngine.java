package com.promptfuzzer.recon.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptfuzzer.config.BrowserTargetConfig;
import com.promptfuzzer.dto.CreateReconTaskRequest;
import com.promptfuzzer.dto.ReconConfig;
import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.entity.ReconApplicationSurface;
import com.promptfuzzer.entity.ReconResult;
import com.promptfuzzer.entity.ReconTask;
import com.promptfuzzer.entity.ReconTrace;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.repository.ReconApplicationSurfaceRepository;
import com.promptfuzzer.repository.ReconResultRepository;
import com.promptfuzzer.repository.ReconTaskRepository;
import com.promptfuzzer.repository.ReconTraceRepository;
import com.promptfuzzer.service.BrowserService;
import com.promptfuzzer.service.HttpRequestService;
import com.promptfuzzer.service.JudgeService;
import com.promptfuzzer.service.ReconPromptService;
import com.promptfuzzer.service.ReconSurfaceDataCodec;
import com.promptfuzzer.service.ReconSurfaceQualityService;
import com.promptfuzzer.service.ReconTraceRecorder;
import com.promptfuzzer.service.TargetExecutionRouter;
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

    private static final int PROMPT_REQUEST_MAX_CHARS = 1200;
    private static final int PROMPT_READ_TARGET_MAX_CHARS = 600;
    private static final int PROMPT_RESPONSE_MAX_CHARS = 1000;
    private static final int PROMPT_VERIFY_RESPONSE_MAX_CHARS = 700;
    private static final int PROMPT_GENERATED_JSON_MAX_CHARS = 1400;
    private static final int HARD_MAX_CAPABILITY_VERIFICATIONS = 64;

    private final ReconTaskRepository taskRepository;
    private final ReconResultRepository resultRepository;
    private final ReconTraceRepository traceRepository;
    private final ReconApplicationSurfaceRepository surfaceRepository;
    private final ReconTraceRecorder traceRecorder;
    private final ReconPromptService promptService;
    private final ReconSurfaceQualityService surfaceQualityService;
    private final BrowserService browserService;
    private final TargetExecutionRouter targetExecutionRouter;
    private final HttpRequestService httpRequestService;
    private final JudgeService judgeService;
    private final ObjectMapper objectMapper;

    public void execute(Long reconTaskId) {
        if (!transitionStatus(
                reconTaskId,
                List.of(ReconTask.Status.PENDING),
                ReconTask.Status.RUNNING,
                null,
                null)) {
            finishCancellationIfRequested(reconTaskId);
            return;
        }
        ReconTask task = requireTask(reconTaskId);

        Long browserSessionId = -reconTaskId;
        boolean browserOpened = false;
        try {
            CreateReconTaskRequest.ReconExecutionConfig config =
                    readExecutionConfig(task.getReconConfig());
            ExecutionControl control = new ExecutionControl(
                    reconTaskId, deadlineFrom(config));
            control.checkpoint();
            ExecutionOutput output;
            if (task.getScanMode() == ReconTask.ScanMode.BROWSER) {
                BrowserTargetConfig browserConfig =
                        objectMapper.readValue(
                                task.getTargetConfig(), BrowserTargetConfig.class);
                browserService.openSession(browserSessionId, browserConfig);
                browserOpened = true;
                control.checkpoint();
                output = executeBrowser(
                        task, config, browserSessionId, control);
            } else {
                output = executeHttp(task, config, control);
            }

            control.checkpoint();
            List<ReconTrace> traces =
                    traceRepository.findByReconTaskIdOrderByIdAsc(task.getId());
            String traceJson =
                    objectMapper.writeValueAsString(toPromptTraceRecords(traces));

            long evidenceStartedAt = System.currentTimeMillis();
            JsonNode evidenceSummary;
            try {
                control.checkpoint();
                evidenceSummary = promptService.summarizeEvidence(
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
                control.checkpoint();
            } catch (TaskControlException e) {
                throw e;
            } catch (Exception e) {
                log.warn("ReconTask {} evidence summary failed; using coverage fallback: {}",
                        task.getId(), e.getMessage());
                traceRecorder.error(
                        task.getId(),
                        ReconTrace.ActionType.EVIDENCE_SUMMARY,
                        output.targetChatIndex(),
                        output.turnIndex() + 1,
                        null,
                        e,
                        evidenceStartedAt);
                evidenceSummary = fallbackEvidenceSummary(
                        task, output.targetDescription(), output.coverageMatrix());
            }

            long synthesisStartedAt = System.currentTimeMillis();
            JsonNode synthesis;
            try {
                control.checkpoint();
                synthesis = promptService.synthesizeApplicationSurfaces(
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
                control.checkpoint();
            } catch (TaskControlException e) {
                throw e;
            } catch (Exception e) {
                log.warn("ReconTask {} application surface synthesis failed; "
                                + "using evidence-summary fallback: {}",
                        task.getId(), e.getMessage());
                traceRecorder.error(
                        task.getId(),
                        ReconTrace.ActionType.APPLICATION_SURFACE_SYNTHESIS,
                        output.targetChatIndex(),
                        output.turnIndex() + 2,
                        null,
                        e,
                        synthesisStartedAt);
                synthesis = fallbackApplicationSurfaceSynthesis(evidenceSummary);
            }

            long qualityStartedAt = System.currentTimeMillis();
            control.checkpoint();
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

            control.checkpoint();
            ReconResult result = saveResult(
                    task,
                    output.openDiscovery(),
                    output.coveragePlan(),
                    output.coverageMatrix(),
                    evidenceSummary,
                    quality.applicationSurfaces(),
                    quality.issues());
            saveApplicationSurfaces(result, quality.applicationSurfaces());

            control.checkpoint();
            if (!transitionStatus(
                    reconTaskId,
                    List.of(ReconTask.Status.RUNNING),
                    ReconTask.Status.COMPLETED,
                    null,
                    LocalDateTime.now())) {
                finishCancellationIfRequested(reconTaskId);
            }
        } catch (TaskControlException e) {
            finishControlledTask(reconTaskId, e.terminalStatus());
        } catch (Exception e) {
            log.error("ReconTask {} failed", reconTaskId, e);
            failTask(reconTaskId, e.getMessage());
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
            Long browserSessionId,
            ExecutionControl control) throws Exception {
        WorkflowState state = new WorkflowState();
        state.targetExecutionMode = config.getTargetExecutionMode() != null
                ? config.getTargetExecutionMode()
                : ReconConfig.TargetExecutionMode.AUTO;
        control.checkpoint();
        long readStartedAt = System.currentTimeMillis();
        String pageContent = browserService.readPage(browserSessionId, null);
        control.checkpoint();
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
                task, config, pageContent, browserSessionId, state, control);
        JsonNode coveragePlan = planCoverage(
                task, config, pageContent, openDiscovery, state, control);
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
            control.checkpoint();
            long startedAt = System.currentTimeMillis();
            state.turnIndex++;
            try {
                BrowserTargetResponse response = sendBrowser(
                        task,
                        browserSessionId,
                        state,
                        message,
                        "Capability discovery returned empty response",
                        startedAt);
                requireResponse(
                        response.extractedText(),
                        "Capability discovery returned empty response");
                ReconTrace trace = traceRecorder.success(
                        task.getId(),
                        ReconTrace.ActionType.CAPABILITY_DISCOVERY,
                        state.targetChatIndex,
                        state.turnIndex,
                        message,
                        response.rawResponse(),
                        response.extractedText(),
                        appendRoutingObservation("按能力域发现目标用途、工具和资源边界", response),
                        ReconTrace.EvidenceType.AGENT_CLAIM,
                        parseExecutionChannel(response.executionChannel()),
                        response.startedAt());
                control.checkpoint();
                addInteraction(
                        discoveryRecords, message, response.extractedText(),
                        "DOMAIN_DISCOVERY", trace);
            } catch (TaskControlException e) {
                throw e;
            } catch (Exception e) {
                recordDiscoveryError(
                        task,
                        ReconTrace.ActionType.CAPABILITY_DISCOVERY,
                        state.targetChatIndex,
                        state.turnIndex,
                        message,
                        startedAt,
                        e,
                        failedExecutionChannel(
                                e, ReconTrace.ExecutionChannel.BROWSER));
                if (browserService.newChat(browserSessionId)) {
                    state.targetChatIndex++;
                }
            }
        }

        JsonNode gapAnalysis = analyzeGaps(
                task, config, coveragePlan, discoveryRecords, state, control);
        for (String message : promptService.gapFollowupMessages(
                gapAnalysis,
                normalize(config.getMaxGapFollowups(), 3, 0, 4))) {
            control.checkpoint();
            long startedAt = System.currentTimeMillis();
            state.turnIndex++;
            try {
                BrowserTargetResponse response = sendBrowser(
                        task,
                        browserSessionId,
                        state,
                        message,
                        "Coverage follow-up returned empty response",
                        startedAt);
                requireResponse(
                        response.extractedText(),
                        "Coverage follow-up returned empty response");
                ReconTrace trace = traceRecorder.success(
                        task.getId(),
                        ReconTrace.ActionType.GAP_FOLLOWUP,
                        state.targetChatIndex,
                        state.turnIndex,
                        message,
                        response.rawResponse(),
                        response.extractedText(),
                        appendRoutingObservation("补探覆盖矩阵中的未知能力域", response),
                        ReconTrace.EvidenceType.AGENT_CLAIM,
                        parseExecutionChannel(response.executionChannel()),
                        response.startedAt());
                control.checkpoint();
                addInteraction(
                        discoveryRecords, message, response.extractedText(),
                        "GAP_FOLLOWUP", trace);
            } catch (TaskControlException e) {
                throw e;
            } catch (Exception e) {
                recordDiscoveryError(
                        task,
                        ReconTrace.ActionType.GAP_FOLLOWUP,
                        state.targetChatIndex,
                        state.turnIndex,
                        message,
                        startedAt,
                        e,
                        failedExecutionChannel(
                                e, ReconTrace.ExecutionChannel.BROWSER));
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
                state,
                control);
        control.checkpoint();
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
            CreateReconTaskRequest.ReconExecutionConfig config,
            ExecutionControl control) throws Exception {
        WorkflowState state = new WorkflowState();
        control.checkpoint();
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
        control.checkpoint();

        JsonNode openDiscovery = executeHttpOpenDiscovery(
                task, config, targetDescription, state, control);
        JsonNode coveragePlan = planCoverage(
                task, config, targetDescription, openDiscovery, state, control);
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
            control.checkpoint();
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
                        ReconTrace.ExecutionChannel.HTTP_TEMPLATE,
                        response.startedAt());
                control.checkpoint();
                addInteraction(
                        discoveryRecords, message, response.extractedText(),
                        "DOMAIN_DISCOVERY", trace);
            } catch (TaskControlException e) {
                throw e;
            } catch (Exception e) {
                recordDiscoveryError(
                        task,
                        ReconTrace.ActionType.CAPABILITY_DISCOVERY,
                        state.targetChatIndex,
                        state.turnIndex,
                        message,
                        startedAt,
                        e,
                        ReconTrace.ExecutionChannel.HTTP_TEMPLATE);
            }
        }

        JsonNode gapAnalysis = analyzeGaps(
                task, config, coveragePlan, discoveryRecords, state, control);
        for (String message : promptService.gapFollowupMessages(
                gapAnalysis,
                normalize(config.getMaxGapFollowups(), 3, 0, 4))) {
            control.checkpoint();
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
                        ReconTrace.ExecutionChannel.HTTP_TEMPLATE,
                        response.startedAt());
                control.checkpoint();
                addInteraction(
                        discoveryRecords, message, response.extractedText(),
                        "GAP_FOLLOWUP", trace);
            } catch (TaskControlException e) {
                throw e;
            } catch (Exception e) {
                recordDiscoveryError(
                        task,
                        ReconTrace.ActionType.GAP_FOLLOWUP,
                        state.targetChatIndex,
                        state.turnIndex,
                        message,
                        startedAt,
                        e,
                        ReconTrace.ExecutionChannel.HTTP_TEMPLATE);
            }
        }

        executeHttpVerifications(
                task,
                config,
                targetDescription,
                coveragePlan,
                gapAnalysis,
                discoveryRecords,
                state,
                control);
        control.checkpoint();
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
            WorkflowState state,
            ExecutionControl control) {
        ObjectNode record = objectMapper.createObjectNode();
        if (!isOpenDiscoveryEnabled(config)) {
            record.put("status", "SKIPPED");
            return record;
        }

        long startedAt = System.currentTimeMillis();
        String message = null;
        state.turnIndex++;
        try {
            control.checkpoint();
            message = promptService.generateOpenCapabilityDiscoveryMessage(
                    task.getReconContext(), targetDescription);
            BrowserTargetResponse response = sendBrowser(
                    task,
                    browserSessionId,
                    state,
                    message,
                    "Open capability discovery returned empty response",
                    startedAt);
            requireResponse(
                    response.extractedText(),
                    "Open capability discovery returned empty response");
            ReconTrace trace = traceRecorder.success(
                    task.getId(),
                    ReconTrace.ActionType.OPEN_CAPABILITY_DISCOVERY,
                    state.targetChatIndex,
                    state.turnIndex,
                    message,
                    response.rawResponse(),
                    response.extractedText(),
                    appendRoutingObservation("开放询问目标整体业务、工具、输入输出和其他能力", response),
                    ReconTrace.EvidenceType.AGENT_CLAIM,
                    parseExecutionChannel(response.executionChannel()),
                    response.startedAt());
            control.checkpoint();
            fillOpenDiscoveryRecord(record, message, response.extractedText(), trace);
        } catch (TaskControlException e) {
            throw e;
        } catch (Exception e) {
            traceRecorder.error(
                    task.getId(),
                    ReconTrace.ActionType.OPEN_CAPABILITY_DISCOVERY,
                    state.targetChatIndex,
                    state.turnIndex,
                    message,
                    e,
                    failedExecutionChannel(
                            e, ReconTrace.ExecutionChannel.BROWSER),
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
            WorkflowState state,
            ExecutionControl control) {
        ObjectNode record = objectMapper.createObjectNode();
        if (!isOpenDiscoveryEnabled(config)) {
            record.put("status", "SKIPPED");
            return record;
        }

        long startedAt = System.currentTimeMillis();
        String message = null;
        state.turnIndex++;
        try {
            control.checkpoint();
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
                    ReconTrace.ExecutionChannel.HTTP_TEMPLATE,
                    response.startedAt());
            control.checkpoint();
            fillOpenDiscoveryRecord(
                    record, message, response.extractedText(), trace);
        } catch (TaskControlException e) {
            throw e;
        } catch (Exception e) {
            traceRecorder.error(
                    task.getId(),
                    ReconTrace.ActionType.OPEN_CAPABILITY_DISCOVERY,
                    state.targetChatIndex,
                    state.turnIndex,
                    message,
                    e,
                    ReconTrace.ExecutionChannel.HTTP_TEMPLATE,
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
            WorkflowState state,
            ExecutionControl control) throws Exception {
        control.checkpoint();
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
        control.checkpoint();
        return plan;
    }

    private JsonNode analyzeGaps(
            ReconTask task,
            CreateReconTaskRequest.ReconExecutionConfig config,
            JsonNode coveragePlan,
            ArrayNode discoveryRecords,
            WorkflowState state,
            ExecutionControl control) throws Exception {
        control.checkpoint();
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
        control.checkpoint();
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
            WorkflowState state,
            ExecutionControl control) throws Exception {
        for (String message : verificationMessages(
                task,
                config,
                targetDescription,
                coveragePlan,
                gapAnalysis,
                discoveryRecords,
                control)) {
            control.checkpoint();
            if (Boolean.TRUE.equals(config.getCleanBetweenVerifications())
                    && browserService.newChat(browserSessionId)) {
                state.targetChatIndex++;
            }
            long startedAt = System.currentTimeMillis();
            state.turnIndex++;
            try {
                BrowserTargetResponse response = sendBrowser(
                        task,
                        browserSessionId,
                        state,
                        message,
                        "Capability verification returned empty response",
                        startedAt);
                requireResponse(
                        response.extractedText(),
                        "Capability verification returned empty response");
                traceRecorder.success(
                        task.getId(),
                        ReconTrace.ActionType.CAPABILITY_VERIFY,
                        state.targetChatIndex,
                        state.turnIndex,
                        message,
                        response.rawResponse(),
                        response.extractedText(),
                        appendRoutingObservation("执行单一、无害的原子能力验证；非空回复不代表验证成功", response),
                        ReconTrace.EvidenceType.AGENT_CLAIM,
                        parseExecutionChannel(response.executionChannel()),
                        response.startedAt());
                control.checkpoint();
            } catch (TaskControlException e) {
                throw e;
            } catch (Exception e) {
                recordVerificationError(
                        task, state.targetChatIndex, state.turnIndex,
                        message,
                        startedAt,
                        e,
                        failedExecutionChannel(
                                e, ReconTrace.ExecutionChannel.BROWSER));
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
            WorkflowState state,
            ExecutionControl control) throws Exception {
        for (String message : verificationMessages(
                task,
                config,
                targetDescription,
                coveragePlan,
                gapAnalysis,
                discoveryRecords,
                control)) {
            control.checkpoint();
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
                        ReconTrace.ExecutionChannel.HTTP_TEMPLATE,
                        response.startedAt());
                control.checkpoint();
            } catch (TaskControlException e) {
                throw e;
            } catch (Exception e) {
                recordVerificationError(
                        task, state.targetChatIndex, state.turnIndex,
                        message,
                        startedAt,
                        e,
                        ReconTrace.ExecutionChannel.HTTP_TEMPLATE);
            }
        }
    }

    private List<String> verificationMessages(
            ReconTask task,
            CreateReconTaskRequest.ReconExecutionConfig config,
            String targetDescription,
            JsonNode coveragePlan,
            JsonNode gapAnalysis,
            ArrayNode discoveryRecords,
            ExecutionControl control) throws Exception {
        control.checkpoint();
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.set("coveragePlan", coveragePlan);
        evidence.set("coverageMatrix", gapAnalysis.path("coverageMatrix"));
        evidence.set("discoveryRecords", discoveryRecords);
        return promptService.generateCapabilityVerificationMessages(
                task.getReconContext(),
                targetDescription,
                evidence.toString(),
                normalize(
                        config.getMaxCapabilityVerifications(),
                        6,
                        0,
                        HARD_MAX_CAPABILITY_VERIFICATIONS));
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

    private ObjectNode fallbackApplicationSurfaceSynthesis(JsonNode evidenceSummary) {
        ObjectNode synthesis = objectMapper.createObjectNode();
        ArrayNode surfaces = objectMapper.createArrayNode();
        synthesis.set("applicationSurfaces", surfaces);

        JsonNode facts = evidenceSummary.path("capabilityFacts");
        if (!facts.isArray() || facts.isEmpty()) {
            return synthesis;
        }

        ObjectNode domain = objectMapper.createObjectNode();
        domain.put("surfaceKey", "RECON_FALLBACK_DOMAIN");
        domain.put("surfaceLevel", "DOMAIN");
        domain.put("title", "RECON 降级能力面");
        domain.put("description",
                "应用面合成超时或失败后，根据覆盖矩阵和能力事实降级生成；需后续复核。");
        domain.put("surfaceType", "OTHER");
        domain.put("selectable", false);
        surfaces.add(domain);

        int index = 0;
        for (JsonNode fact : facts) {
            String capabilityKey = fact.path("capabilityKey").asText("").trim();
            if (capabilityKey.isBlank()) {
                continue;
            }
            ObjectNode capability = objectMapper.createObjectNode();
            capability.put("surfaceKey", "RECON_FALLBACK_" + (++index));
            capability.put("surfaceLevel", "CAPABILITY");
            capability.put("parentSurfaceKey", "RECON_FALLBACK_DOMAIN");
            capability.put("title", fallbackCapabilityTitle(capabilityKey));
            String description = fallbackCapabilityDescription(fact);
            capability.put("displayDescription", description);
            capability.put("description", description);
            capability.put("surfaceType", fact.path("domain").asText("OTHER"));
            capability.put("relatedTool", fact.path("relatedTool").asText(""));
            capability.set("supportedActions",
                    objectMapper.valueToTree(List.of(fact.path("action").asText(""))));
            capability.put("resourceScope", fact.path("resourceScope").asText(""));
            capability.set("resourceObjects", objectMapper.valueToTree(
                    List.of(fact.path("object").asText(""))));
            String relatedTool = fact.path("relatedTool").asText("").trim();
            capability.set("entrypoints", objectMapper.valueToTree(
                    relatedTool.isBlank() ? List.of() : List.of(relatedTool)));
            capability.set("permissionBoundaries", objectMapper.createArrayNode());
            capability.set("controlBoundaries", objectMapper.createArrayNode());
            capability.set("capabilityKeys",
                    objectMapper.valueToTree(List.of(capabilityKey)));
            capability.set("sourceTraceIds", fact.path("sourceTraceIds").deepCopy());
            surfaces.add(capability);
        }
        return synthesis;
    }

    private String fallbackCapabilityDescription(JsonNode fact) {
        String object = fact.path("object").asText("").trim();
        String action = fact.path("action").asText("").trim();
        String relatedTool = fact.path("relatedTool").asText("").trim();
        List<String> clauses = new ArrayList<>();
        if (!relatedTool.isBlank()) {
            clauses.add("通过" + relatedTool);
        }
        if (!object.isBlank() && !action.isBlank()) {
            clauses.add("对" + object + "执行" + action);
        } else if (!object.isBlank()) {
            clauses.add("处理" + object);
        } else if (!action.isBlank()) {
            clauses.add("支持" + action);
        }
        return clauses.isEmpty()
                ? "已发现该业务能力，具体对象与入口尚未明确。"
                : String.join("，", clauses) + "。";
    }

    private ObjectNode fallbackEvidenceSummary(
            ReconTask task,
            String targetDescription,
            JsonNode coverageMatrix) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("targetFingerprint",
                targetDescription != null && !targetDescription.isBlank()
                        ? targetDescription : "recon-fallback-target");

        ObjectNode businessProfile = summary.putObject("businessProfile");
        businessProfile.put("primaryPurpose",
                task.getReconContext() != null && !task.getReconContext().isBlank()
                        ? task.getReconContext() : "RECON fallback summary");
        businessProfile.set("businessObjects", objectMapper.createArrayNode());

        ObjectNode responseProfile = summary.putObject("responseProfile");
        responseProfile.put("primaryFormat", "fallback");
        responseProfile.put("source", "coverageMatrix");

        summary.set("capabilityInventory", coverageMatrix.deepCopy());
        summary.set("toolInventory", objectMapper.createArrayNode());
        summary.set("unresolvedCapabilities", objectMapper.createArrayNode());
        summary.set("supportingEvidence", objectMapper.createArrayNode());
        summary.set("unresolvedQuestions", objectMapper.createArrayNode());
        summary.put("qualityStatus", "PARTIAL");

        ArrayNode facts = summary.putArray("capabilityFacts");
        JsonNode matrix = coverageMatrix.isArray()
                ? coverageMatrix
                : coverageMatrix.path("coverageMatrix");
        if (matrix.isArray()) {
            for (JsonNode item : matrix) {
                String domain = item.path("domain").asText("UNKNOWN");
                ObjectNode fact = facts.addObject();
                fact.put("capabilityKey", domain + ".FALLBACK");
                fact.put("domain", domain);
                fact.put("action", "OBSERVE");
                fact.put("object", domain);
                fact.put("description", item.path("evidenceSummary").asText(
                        "Fallback capability fact generated from coverage matrix."));
                fact.put("relatedTool", "");
                fact.put("resourceScope", "");
                fact.put("evidenceLevel", item.path("status").asText("CLAIMED"));
                fact.set("sourceTraceIds", item.path("sourceTraceIds").deepCopy());
            }
        }
        return summary;
    }

    private String fallbackCapabilityTitle(String capabilityKey) {
        String domain = capabilityKey == null ? "" : capabilityKey.split("\\.")[0];
        String title = switch (domain) {
            case "BUSINESS_QUERY" -> "业务查询能力（降级推断）";
            case "FILE_IO" -> "文件处理能力（降级推断）";
            case "TOOL_ECOSYSTEM" -> "工具生态能力（降级推断）";
            case "REPORT_VISUALIZATION" -> "报告与可视化能力（降级推断）";
            case "CODE_EXECUTION" -> "代码执行能力（降级推断）";
            case "SANDBOX_WORKSPACE" -> "沙箱工作区能力（降级推断）";
            case "OTHER_DISCOVERED" -> "其他发现能力（降级推断）";
            default -> "";
        };
        if (!title.isBlank()) {
            return title;
        }
        String normalized = capabilityKey == null ? "" : capabilityKey
                .replace('.', ' ')
                .replace('_', ' ')
                .replace('-', ' ')
                .replace("FALLBACK", "")
                .trim();
        return normalized.isBlank() ? "发现能力（降级推断）" : normalized + "（降级推断）";
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
        surface.setSupportedActions(ReconSurfaceDataCodec.writeStringArray(
                node.path("supportedActions")));
        surface.setResourceScope(node.path("resourceScope").asText(""));
        surface.setResourceObjects(ReconSurfaceDataCodec.writeStringArray(
                node.path("resourceObjects")));
        surface.setEntrypoints(ReconSurfaceDataCodec.writeStringArray(
                node.path("entrypoints")));
        surface.setPermissionBoundaries(ReconSurfaceDataCodec.writeStringArray(
                node.path("permissionBoundaries")));
        surface.setControlBoundaries(ReconSurfaceDataCodec.writeStringArray(
                node.path("controlBoundaries")));
        surface.setEvidenceSource(parseEvidenceSource(
                node.path("evidenceSource").asText("AGENT_CLAIM")));
        surface.setVerificationStatus(parseVerificationStatus(
                node.path("verificationStatus").asText("UNVERIFIED")));
        surface.setSourceTraceIds(ReconSurfaceDataCodec.writeLongArray(
                node.path("sourceTraceIds")));
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

    private BrowserTargetResponse sendBrowser(
            ReconTask reconTask,
            Long browserSessionId,
            WorkflowState workflowState,
            String message,
            String emptyResponseMessage,
            long startedAt) {
        Task routingTask = toRoutingTask(reconTask);
        AgentWorkflowContext context = AgentWorkflowContext.builder()
                .task(routingTask)
                .browserMode(true)
                .build();
        AgentWorkflowState state = AgentWorkflowState.builder()
                .taskId(routingTask.getId())
                .phase("RECON")
                .turnIndex(workflowState.turnIndex)
                .retryIndex(0)
                .targetChatIndex(workflowState.targetChatIndex)
                .message(message)
                .build();
        TargetExecutionRouter.TargetExecutionResult result =
                targetExecutionRouter.send(
                        context,
                        state,
                        browserSessionId,
                        message,
                        workflowState.targetExecutionMode);
        String extractedText = result.getExtractedText() != null
                && !result.getExtractedText().isBlank()
                ? result.getExtractedText()
                : result.getRawResponse();
        try {
            requireResponse(extractedText, emptyResponseMessage);
        } catch (IllegalStateException e) {
            throw new TargetExecutionChannelException(
                    e,
                    parseExecutionChannel(result.getExecutionChannel()));
        }
        return new BrowserTargetResponse(
                result.getRawResponse(),
                extractedText,
                result.getExecutionChannel(),
                result.getReplayProfileId(),
                result.getReplayProfileClassification(),
                result.getFallbackType(),
                result.getFallbackReason(),
                result.getStatusCode(),
                result.getContentType(),
                startedAt);
    }

    private ReconTrace.ExecutionChannel parseExecutionChannel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ReconTrace.ExecutionChannel.valueOf(
                    value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private ReconTrace.ExecutionChannel failedExecutionChannel(
            Exception error,
            ReconTrace.ExecutionChannel fallback) {
        if (error instanceof TargetExecutionChannelException channelError
                && channelError.executionChannel() != null) {
            return channelError.executionChannel();
        }
        return fallback;
    }

    private Task toRoutingTask(ReconTask reconTask) {
        Task task = new Task();
        task.setId(-Math.abs(reconTask.getId()));
        task.setName("RECON:" + reconTask.getName());
        task.setScanMode(Task.ScanMode.BROWSER);
        task.setTargetConfig(reconTask.getTargetConfig());
        task.setAttackContext(reconTask.getReconContext());
        task.setExternalIntelligence(reconTask.getExternalIntelligence());
        task.setReconConfig(reconTask.getReconConfig());
        return task;
    }

    private String appendRoutingObservation(
            String observation,
            BrowserTargetResponse response) {
        List<String> parts = new ArrayList<>();
        if (response.executionChannel() != null) {
            parts.add("executionChannel=" + response.executionChannel());
        }
        if (response.replayProfileId() != null) {
            parts.add("replayProfileId=" + response.replayProfileId());
        }
        if (response.replayProfileClassification() != null) {
            parts.add("replayProfileClassification=" + response.replayProfileClassification());
        }
        if (response.statusCode() != null) {
            parts.add("statusCode=" + response.statusCode());
        }
        if (response.contentType() != null && !response.contentType().isBlank()) {
            parts.add("contentType=" + response.contentType());
        }
        if (response.fallbackType() != null && !response.fallbackType().isBlank()) {
            parts.add("fallbackType=" + response.fallbackType());
        }
        if (response.fallbackReason() != null && !response.fallbackReason().isBlank()) {
            parts.add("fallbackReason=" + response.fallbackReason());
        }
        if (parts.isEmpty()) {
            return observation;
        }
        return observation + "；" + String.join(", ", parts);
    }

    private void recordVerificationError(
            ReconTask task,
            int targetChatIndex,
            int turnIndex,
            String verificationMessage,
            long startedAt,
            Exception error,
            ReconTrace.ExecutionChannel executionChannel) {
        traceRecorder.error(
                task.getId(),
                ReconTrace.ActionType.CAPABILITY_VERIFY,
                targetChatIndex,
                turnIndex,
                verificationMessage,
                error,
                executionChannel,
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
            Exception error,
            ReconTrace.ExecutionChannel executionChannel) {
        traceRecorder.error(
                task.getId(),
                actionType,
                targetChatIndex,
                turnIndex,
                discoveryMessage,
                error,
                executionChannel,
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
            record.put("evidenceType", trace.getEvidenceType());
            record.put("status", trace.getStatus());
            if (trace.getErrorMessage() != null && !trace.getErrorMessage().isBlank()) {
                record.put("errorMessage", compactText(trace.getErrorMessage(), PROMPT_RESPONSE_MAX_CHARS));
            }
            if (trace.getActionType() == ReconTrace.ActionType.READ_TARGET) {
                record.put("observation", trace.getObservation());
                record.put("pageSummary", compactReadTarget(trace));
            } else {
                record.put("requestMessage",
                        compactText(trace.getRequestMessage(), PROMPT_REQUEST_MAX_CHARS));
                record.put("response", compactTraceResponse(trace));
                record.put("observation", trace.getObservation());
            }
            records.add(record);
        }
        return records;
    }

    private String compactReadTarget(ReconTrace trace) {
        return compactText(firstNonBlank(trace.getExtractedText(), trace.getRawResponse()),
                PROMPT_READ_TARGET_MAX_CHARS);
    }

    private String compactTraceResponse(ReconTrace trace) {
        String response = firstNonBlank(trace.getExtractedText(), trace.getRawResponse());
        if (response == null || response.isBlank()) {
            return "";
        }
        ReconTrace.ActionType actionType = trace.getActionType();
        if (actionType == ReconTrace.ActionType.CAPABILITY_VERIFY) {
            return compactVerificationResponse(response);
        }
        if (actionType == ReconTrace.ActionType.COVERAGE_PLAN
                || actionType == ReconTrace.ActionType.COVERAGE_GAP_ANALYSIS
                || actionType == ReconTrace.ActionType.EVIDENCE_SUMMARY
                || actionType == ReconTrace.ActionType.APPLICATION_SURFACE_SYNTHESIS
                || actionType == ReconTrace.ActionType.SURFACE_QUALITY_GATE) {
            return compactText(response, PROMPT_GENERATED_JSON_MAX_CHARS);
        }
        return compactText(response, PROMPT_RESPONSE_MAX_CHARS);
    }

    private String compactVerificationResponse(String response) {
        String normalized = normalizePromptText(response);
        List<String> signals = new ArrayList<>();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (normalized.contains("|") && normalized.contains("---")) {
            signals.add("包含 Markdown 表格");
        }
        if (lower.contains("<html") || lower.contains("<body")
                || lower.contains("<div") || lower.contains("<table")) {
            signals.add("包含 HTML 片段");
        }
        String trimmed = normalized.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            signals.add("包含 JSON 或工具调用片段");
        }
        if (lower.contains("python") || lower.contains("command")
                || lower.contains("sandbox") || lower.contains("沙箱")) {
            signals.add("提及代码/沙箱执行");
        }
        String sample = compactText(normalized, PROMPT_VERIFY_RESPONSE_MAX_CHARS);
        if (signals.isEmpty()) {
            return sample;
        }
        return "结构信号：" + String.join("、", signals) + "；响应样例：" + sample;
    }

    private String compactText(String value, int maxChars) {
        String normalized = normalizePromptText(value);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...[已截断，原始长度=" + normalized.length() + "]";
    }

    private String normalizePromptText(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\r", "\n")
                .replaceAll("[ \\t\\f\\u000B]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
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

    private ReconTask requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "ReconTask not found: " + taskId));
    }

    private boolean transitionStatus(
            Long taskId,
            List<ReconTask.Status> expectedStatuses,
            ReconTask.Status newStatus,
            String errorMessage,
            LocalDateTime completedAt) {
        return taskRepository.transitionStatus(
                taskId,
                expectedStatuses,
                newStatus,
                errorMessage,
                completedAt,
                LocalDateTime.now()) > 0;
    }

    private void finishCancellationIfRequested(Long taskId) {
        transitionStatus(
                taskId,
                List.of(ReconTask.Status.CANCEL_REQUESTED),
                ReconTask.Status.CANCELLED,
                null,
                LocalDateTime.now());
    }

    private void finishControlledTask(
            Long taskId, ReconTask.Status terminalStatus) {
        if (terminalStatus == ReconTask.Status.CANCELLED) {
            finishCancellationIfRequested(taskId);
            return;
        }
        if (terminalStatus == ReconTask.Status.TIMED_OUT) {
            boolean timedOut = transitionStatus(
                    taskId,
                    List.of(ReconTask.Status.RUNNING),
                    ReconTask.Status.TIMED_OUT,
                    "RECON task exceeded taskTimeoutSeconds",
                    LocalDateTime.now());
            if (!timedOut) {
                finishCancellationIfRequested(taskId);
            }
        }
    }

    private void failTask(Long taskId, String errorMessage) {
        boolean failed = transitionStatus(
                taskId,
                List.of(ReconTask.Status.RUNNING),
                ReconTask.Status.FAILED,
                errorMessage,
                LocalDateTime.now());
        if (!failed) {
            finishCancellationIfRequested(taskId);
        }
    }

    private Long deadlineFrom(
            CreateReconTaskRequest.ReconExecutionConfig config) {
        Integer timeoutSeconds = config.getTaskTimeoutSeconds();
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            return null;
        }
        return System.currentTimeMillis() + timeoutSeconds * 1000L;
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

    private record BrowserTargetResponse(
            String rawResponse,
            String extractedText,
            String executionChannel,
            Long replayProfileId,
            String replayProfileClassification,
            String fallbackType,
            String fallbackReason,
            Integer statusCode,
            String contentType,
            long startedAt) {
    }

    private static final class WorkflowState {
        private int targetChatIndex;
        private int turnIndex;
        private ReconConfig.TargetExecutionMode targetExecutionMode =
                ReconConfig.TargetExecutionMode.AUTO;
    }

    private final class ExecutionControl {
        private final Long taskId;
        private final Long deadlineEpochMs;

        private ExecutionControl(Long taskId, Long deadlineEpochMs) {
            this.taskId = taskId;
            this.deadlineEpochMs = deadlineEpochMs;
        }

        private void checkpoint() {
            ReconTask current = requireTask(taskId);
            if (current.getStatus() == ReconTask.Status.CANCEL_REQUESTED
                    || current.getStatus() == ReconTask.Status.CANCELLED) {
                throw new TaskControlException(
                        ReconTask.Status.CANCELLED);
            }
            if (deadlineEpochMs != null
                    && System.currentTimeMillis() >= deadlineEpochMs) {
                throw new TaskControlException(
                        ReconTask.Status.TIMED_OUT);
            }
        }
    }

    private static final class TaskControlException
            extends RuntimeException {
        private final ReconTask.Status terminalStatus;

        private TaskControlException(ReconTask.Status terminalStatus) {
            super(terminalStatus.name());
            this.terminalStatus = terminalStatus;
        }

        private ReconTask.Status terminalStatus() {
            return terminalStatus;
        }
    }

    private static final class TargetExecutionChannelException
            extends RuntimeException {
        private final ReconTrace.ExecutionChannel executionChannel;

        private TargetExecutionChannelException(
                RuntimeException cause,
                ReconTrace.ExecutionChannel executionChannel) {
            super(cause.getMessage(), cause);
            this.executionChannel = executionChannel;
        }

        private ReconTrace.ExecutionChannel executionChannel() {
            return executionChannel;
        }
    }
}
