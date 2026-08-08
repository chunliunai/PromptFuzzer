package com.promptfuzzer.recon.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReconWorkflowEngineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void browserWorkflowPlansCoverageAndPersistsHierarchicalSurfaces()
            throws Exception {
        Fixtures fixtures = fixtures();
        ReconTask task = browserTask();
        when(fixtures.taskRepository.findById(12L))
                .thenReturn(Optional.of(task));
        when(fixtures.browserService.readPage(-12L, null))
                .thenReturn("Agent page");
        stubPromptFlow(fixtures.promptService);
        when(fixtures.browserService.sendMessage(-12L, "Ask all"))
                .thenReturn("I support business, files, Bash and tools.");
        when(fixtures.browserService.sendMessage(-12L, "Ask business"))
                .thenReturn("I query orders with order_mcp.");
        when(fixtures.browserService.sendMessage(-12L, "Ask code"))
                .thenReturn("I support Bash in a sandbox.");
        when(fixtures.browserService.sendMessage(-12L, "Ask files"))
                .thenReturn("I can export CSV files.");
        when(fixtures.browserService.newChat(-12L)).thenReturn(true);
        when(fixtures.browserService.sendMessage(-12L, "Run harmless Bash"))
                .thenReturn("recon-ok");
        stubPersistence(fixtures, 99L);

        fixtures.engine.execute(12L);

        assertEquals(ReconTask.Status.COMPLETED, task.getStatus());
        assertFalse(task.getCompletedAt() == null);
        verify(fixtures.browserService).openSession(eq(-12L), any());
        verify(fixtures.browserService).closeSession(-12L);
        verify(fixtures.promptService)
                .generateOpenCapabilityDiscoveryMessage(
                        "survey capabilities", "Agent page");
        verify(fixtures.promptService).planCoverage(
                eq("survey capabilities"),
                isNull(),
                eq("Agent page"),
                contains("support business"),
                eq(4));
        verify(fixtures.promptService).analyzeCoverageGaps(
                eq("survey capabilities"), any(), contains("support Bash"), eq(3));
        verify(fixtures.promptService).summarizeEvidence(
                eq("survey capabilities"), isNull(), eq("Agent page"),
                anyString(), anyString());
        verify(fixtures.promptService).synthesizeApplicationSurfaces(
                any(), anyString());

        ArgumentCaptor<ReconApplicationSurface> surfaceCaptor =
                ArgumentCaptor.forClass(ReconApplicationSurface.class);
        verify(fixtures.surfaceRepository, times(2))
                .save(surfaceCaptor.capture());
        List<ReconApplicationSurface> saved = surfaceCaptor.getAllValues();
        assertEquals(ReconApplicationSurface.SurfaceLevel.DOMAIN,
                saved.get(0).getSurfaceLevel());
        assertEquals(false, saved.get(0).getSelectable());
        assertEquals(ReconApplicationSurface.SurfaceLevel.CAPABILITY,
                saved.get(1).getSurfaceLevel());
        assertEquals(201L, saved.get(1).getParentSurfaceId());
        assertEquals(ReconApplicationSurface.VerificationStatus.VERIFIED,
                saved.get(1).getVerificationStatus());
    }

    @Test
    void browserWorkflowContinuesWhenOpenDiscoveryFails()
            throws Exception {
        Fixtures fixtures = fixtures();
        ReconTask task = browserTask();
        when(fixtures.taskRepository.findById(12L))
                .thenReturn(Optional.of(task));
        when(fixtures.browserService.readPage(-12L, null))
                .thenReturn("Agent page");
        stubPromptFlow(fixtures.promptService);
        when(fixtures.browserService.sendMessage(-12L, "Ask all"))
                .thenReturn("");
        when(fixtures.browserService.sendMessage(-12L, "Ask business"))
                .thenReturn("I query orders with order_mcp.");
        when(fixtures.browserService.sendMessage(-12L, "Ask code"))
                .thenReturn("I support Bash in a sandbox.");
        when(fixtures.browserService.sendMessage(-12L, "Ask files"))
                .thenReturn("I can export CSV files.");
        when(fixtures.browserService.newChat(-12L)).thenReturn(true);
        when(fixtures.browserService.sendMessage(-12L, "Run harmless Bash"))
                .thenReturn("recon-ok");
        stubPersistence(fixtures, 102L);

        fixtures.engine.execute(12L);

        assertEquals(ReconTask.Status.COMPLETED, task.getStatus());
        verify(fixtures.traceRecorder).error(
                eq(12L),
                eq(ReconTrace.ActionType.OPEN_CAPABILITY_DISCOVERY),
                eq(0),
                eq(2),
                eq("Ask all"),
                any(IllegalStateException.class),
                anyLong());
        verify(fixtures.promptService).planCoverage(
                eq("survey capabilities"),
                isNull(),
                eq("Agent page"),
                contains("\"status\":\"ERROR\""),
                eq(4));
    }

    @Test
    void browserWorkflowContinuesWhenOneDiscoveryTimesOut()
            throws Exception {
        Fixtures fixtures = fixtures();
        ReconTask task = browserTask();
        when(fixtures.taskRepository.findById(12L))
                .thenReturn(Optional.of(task));
        when(fixtures.browserService.readPage(-12L, null))
                .thenReturn("Agent page");
        stubPromptFlow(fixtures.promptService);
        when(fixtures.browserService.sendMessage(-12L, "Ask all"))
                .thenReturn("I support business, files, Bash and tools.");
        when(fixtures.browserService.sendMessage(-12L, "Ask business"))
                .thenReturn("");
        when(fixtures.browserService.sendMessage(-12L, "Ask code"))
                .thenReturn("I support Bash in a sandbox.");
        when(fixtures.browserService.sendMessage(-12L, "Ask files"))
                .thenReturn("I can export CSV files.");
        when(fixtures.browserService.newChat(-12L)).thenReturn(true);
        when(fixtures.browserService.sendMessage(-12L, "Run harmless Bash"))
                .thenReturn("recon-ok");
        stubPersistence(fixtures, 101L);

        fixtures.engine.execute(12L);

        assertEquals(ReconTask.Status.COMPLETED, task.getStatus());
        verify(fixtures.traceRecorder).error(
                eq(12L),
                eq(ReconTrace.ActionType.CAPABILITY_DISCOVERY),
                eq(0),
                eq(4),
                eq("Ask business"),
                any(IllegalStateException.class),
                anyLong());
        verify(fixtures.promptService).analyzeCoverageGaps(
                eq("survey capabilities"),
                any(),
                contains("support Bash"),
                eq(3));
        verify(fixtures.browserService).closeSession(-12L);
    }

    @Test
    void httpWorkflowUsesSameCoverageAndSynthesisPipeline() throws Exception {
        Fixtures fixtures = fixtures();
        ReconTask task = httpTask();
        when(fixtures.taskRepository.findById(12L))
                .thenReturn(Optional.of(task));
        stubPromptFlow(fixtures.promptService);
        stubHttpResponse(fixtures, task, "Ask all",
                "I support business, files, Bash and tools.");
        stubHttpResponse(fixtures, task, "Ask business",
                "I query orders with order_mcp.");
        stubHttpResponse(fixtures, task, "Ask code",
                "I support Bash in a sandbox.");
        stubHttpResponse(fixtures, task, "Ask files",
                "I can export CSV files.");
        stubHttpResponse(fixtures, task, "Run harmless Bash", "recon-ok");
        stubPersistence(fixtures, 100L);

        fixtures.engine.execute(12L);

        assertEquals(ReconTask.Status.COMPLETED, task.getStatus());
        verifyNoInteractions(fixtures.browserService);
        verify(fixtures.httpRequestService)
                .send(task.getRawRequestTemplate(), "Run harmless Bash");
        verify(fixtures.traceRecorder).success(
                eq(12L),
                eq(ReconTrace.ActionType.COVERAGE_PLAN),
                eq(0), anyInt(), isNull(), anyString(), anyString(),
                anyString(), eq(ReconTrace.EvidenceType.EXTERNAL_EVIDENCE),
                anyLong());
        verify(fixtures.traceRecorder).success(
                eq(12L),
                eq(ReconTrace.ActionType.SURFACE_QUALITY_GATE),
                eq(0), anyInt(), isNull(), anyString(), anyString(),
                anyString(), eq(ReconTrace.EvidenceType.OBSERVED), anyLong());
    }

    private void stubPromptFlow(ReconPromptService promptService)
            throws Exception {
        when(promptService.generateOpenCapabilityDiscoveryMessage(
                any(), any())).thenReturn("Ask all");
        JsonNode coveragePlan = objectMapper.readTree("""
                {
                  "domains": [
                    {"domain": "BUSINESS_QUERY", "status": "UNKNOWN"},
                    {"domain": "CODE_EXECUTION", "status": "UNKNOWN"}
                  ],
                  "discoveryMessages": ["Ask business", "Ask code"]
                }
                """);
        when(promptService.planCoverage(
                any(), any(), any(), any(), anyInt()))
                .thenReturn(coveragePlan);
        when(promptService.discoveryMessages(coveragePlan, 4))
                .thenReturn(List.of("Ask business", "Ask code"));

        JsonNode gapAnalysis = objectMapper.readTree("""
                {
                  "coverageMatrix": [
                    {"domain": "BUSINESS_QUERY", "status": "CLAIMED"},
                    {"domain": "CODE_EXECUTION", "status": "CLAIMED"},
                    {"domain": "FILE_IO", "status": "UNKNOWN"}
                  ],
                  "followupMessages": ["Ask files"]
                }
                """);
        when(promptService.analyzeCoverageGaps(
                any(), eq(coveragePlan), anyString(), anyInt()))
                .thenReturn(gapAnalysis);
        when(promptService.gapFollowupMessages(gapAnalysis, 3))
                .thenReturn(List.of("Ask files"));
        when(promptService.generateCapabilityVerificationMessages(
                any(), any(), anyString(), eq(6)))
                .thenReturn(List.of("Run harmless Bash"));

        JsonNode evidenceSummary = objectMapper.readTree("""
                {
                  "targetFingerprint": "order-agent",
                  "businessProfile": {"primaryPurpose": "orders"},
                  "responseProfile": {"language": "English"},
                  "capabilityInventory": ["Bash execution"],
                  "capabilityFacts": [{
                    "capabilityKey": "CODE_EXECUTION.EXECUTE.BASH_COMMAND",
                    "domain": "CODE_EXECUTION",
                    "action": "EXECUTE",
                    "object": "BASH_COMMAND",
                    "evidenceLevel": "RESULT_OBSERVED",
                    "sourceTraceIds": [5]
                  }],
                  "toolInventory": ["order_mcp"],
                  "unresolvedCapabilities": [],
                  "supportingEvidence": [],
                  "unresolvedQuestions": [],
                  "qualityStatus": "SUFFICIENT"
                }
                """);
        when(promptService.summarizeEvidence(
                any(), any(), any(), anyString(), anyString()))
                .thenReturn(evidenceSummary);

        JsonNode synthesis = objectMapper.readTree("""
                {
                  "applicationSurfaces": [
                    {
                      "surfaceKey": "EXECUTION",
                      "parentSurfaceKey": "",
                      "surfaceLevel": "DOMAIN",
                      "selectable": false,
                      "title": "代码与命令执行",
                      "description": "执行能力域",
                      "surfaceType": "CODE_EXECUTION",
                      "supportedActions": [],
                      "sourceTraceIds": [],
                      "capabilityKeys": []
                    },
                    {
                      "surfaceKey": "EXECUTION.BASH",
                      "parentSurfaceKey": "EXECUTION",
                      "surfaceLevel": "CAPABILITY",
                      "selectable": true,
                      "title": "支持 Bash 命令执行",
                      "description": "已完成无害验证",
                      "surfaceType": "CODE_EXECUTION",
                      "supportedActions": ["execute"],
                      "sourceTraceIds": [5],
                      "capabilityKeys": [
                        "CODE_EXECUTION.EXECUTE.BASH_COMMAND"
                      ]
                    }
                  ]
                }
                """);
        when(promptService.synthesizeApplicationSurfaces(
                eq(evidenceSummary), anyString())).thenReturn(synthesis);
    }

    private void stubHttpResponse(
            Fixtures fixtures,
            ReconTask task,
            String message,
            String extractedText) throws Exception {
        String raw = "HTTP 200\n{\"reply\":\"" + extractedText + "\"}";
        when(fixtures.httpRequestService.send(
                task.getRawRequestTemplate(), message)).thenReturn(raw);
        JudgeService.JudgeResult result = new JudgeService.JudgeResult();
        result.setExtractedText(extractedText);
        when(fixtures.judgeService.extractOnly(raw)).thenReturn(result);
    }

    private void stubPersistence(Fixtures fixtures, Long resultId) {
        ReconTrace trace = new ReconTrace();
        trace.setId(5L);
        trace.setActionType(ReconTrace.ActionType.CAPABILITY_VERIFY);
        trace.setStatus(ReconTrace.Status.SUCCESS);
        when(fixtures.traceRepository.findByReconTaskIdOrderByIdAsc(12L))
                .thenReturn(List.of(trace));
        when(fixtures.resultRepository.findByReconTaskId(12L))
                .thenReturn(Optional.empty());
        when(fixtures.resultRepository.save(any(ReconResult.class)))
                .thenAnswer(invocation -> {
                    ReconResult result = invocation.getArgument(0);
                    result.setId(resultId);
                    return result;
                });
        AtomicLong surfaceId = new AtomicLong(200);
        when(fixtures.surfaceRepository.save(any(ReconApplicationSurface.class)))
                .thenAnswer(invocation -> {
                    ReconApplicationSurface surface = invocation.getArgument(0);
                    surface.setId(surfaceId.incrementAndGet());
                    return surface;
                });
    }

    private Fixtures fixtures() {
        ReconTaskRepository taskRepository = mock(ReconTaskRepository.class);
        ReconResultRepository resultRepository =
                mock(ReconResultRepository.class);
        ReconTraceRepository traceRepository =
                mock(ReconTraceRepository.class);
        ReconApplicationSurfaceRepository surfaceRepository =
                mock(ReconApplicationSurfaceRepository.class);
        ReconTraceRecorder traceRecorder = mock(ReconTraceRecorder.class);
        ReconPromptService promptService = mock(ReconPromptService.class);
        BrowserService browserService = mock(BrowserService.class);
        HttpRequestService httpRequestService = mock(HttpRequestService.class);
        JudgeService judgeService = mock(JudgeService.class);
        ReconSurfaceQualityService qualityService =
                new ReconSurfaceQualityService(objectMapper);
        ReconWorkflowEngine engine = new ReconWorkflowEngine(
                taskRepository,
                resultRepository,
                traceRepository,
                surfaceRepository,
                traceRecorder,
                promptService,
                qualityService,
                browserService,
                httpRequestService,
                judgeService,
                objectMapper);
        return new Fixtures(
                taskRepository,
                resultRepository,
                traceRepository,
                surfaceRepository,
                traceRecorder,
                promptService,
                browserService,
                httpRequestService,
                judgeService,
                engine);
    }

    private ReconTask browserTask() {
        ReconTask task = baseTask();
        task.setScanMode(ReconTask.ScanMode.BROWSER);
        task.setTargetConfig("""
                {
                  "chatUrl": "https://example.com/chat",
                  "targetType": "test",
                  "selectors": {"input": "#input"}
                }
                """);
        return task;
    }

    private ReconTask httpTask() {
        ReconTask task = baseTask();
        task.setScanMode(ReconTask.ScanMode.HTTP_TEMPLATE);
        task.setRawRequestTemplate("""
                POST /chat HTTP/1.1
                Host: agent.example.com
                Content-Type: application/json

                {"message":"{{PAYLOAD}}"}
                """);
        return task;
    }

    private ReconTask baseTask() {
        ReconTask task = new ReconTask();
        task.setId(12L);
        task.setName("recon");
        task.setReconContext("survey capabilities");
        task.setReconConfig("""
                {
                  "maxDiscoveryMessages": 4,
                  "maxGapFollowups": 3,
                  "maxCapabilityVerifications": 6,
                  "cleanBetweenVerifications": true
                }
                """);
        return task;
    }

    private record Fixtures(
            ReconTaskRepository taskRepository,
            ReconResultRepository resultRepository,
            ReconTraceRepository traceRepository,
            ReconApplicationSurfaceRepository surfaceRepository,
            ReconTraceRecorder traceRecorder,
            ReconPromptService promptService,
            BrowserService browserService,
            HttpRequestService httpRequestService,
            JudgeService judgeService,
            ReconWorkflowEngine engine) {
    }
}
