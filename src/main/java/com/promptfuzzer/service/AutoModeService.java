package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.config.BrowserTargetConfig;
import com.promptfuzzer.dto.CreateTaskRequest;
import com.promptfuzzer.dto.TaskResponse;
import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.ScanPayload;
import com.promptfuzzer.entity.ScanResult;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.repository.AgentSessionRepository;
import com.promptfuzzer.repository.ScanPayloadRepository;
import com.promptfuzzer.repository.ScanResultRepository;
import com.promptfuzzer.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates AUTO mode: Phase 1 scout → Phase 2 Agent siege.
 * HTTP mode: Phase 1 uses ScanExecutorService (concurrent HTTP payloads).
 * Browser mode: Phase 1 uses BrowserSingleTurnService (sequential browser messages).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoModeService {

    private final TaskRepository taskRepository;
    private final ScanPayloadRepository scanPayloadRepository;
    private final ScanResultRepository scanResultRepository;
    private final AgentSessionRepository agentSessionRepository;
    private final ScanExecutorService scanExecutorService;
    private final AgentSessionExecutor agentSessionExecutor;
    private final PayloadGeneratorService payloadGeneratorService;
    private final BrowserSingleTurnService browserSingleTurnService;
    private final BrowserService browserService;
    private final ReconIntelligenceService reconIntelligenceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TaskResponse executeAutoMode(Task task, CreateTaskRequest request, boolean forcePhase2) {
        final Long taskId = task.getId();
        boolean isBrowser = task.getScanMode() == Task.ScanMode.BROWSER;
        List<String> goalIds = request.getGoalIds();
        List<String> techniqueIds = request.getTechniqueIds();
        String attackContext = request.getAttackContext();

        task.setStatus(Task.TaskStatus.RUNNING);
        task.setExternalIntelligence(request.getExternalIntelligence());
        if (request.getReconConfig() != null) {
            try {
                task.setReconConfig(objectMapper.writeValueAsString(request.getReconConfig()));
            } catch (Exception e) {
                log.warn("Failed to serialize reconConfig for AUTO task {}", taskId, e);
            }
        }
        taskRepository.save(task);

        // -- Phase 1: Scout --
        boolean phase1Success;
        Long browserSessionId = null;
        BrowserTargetConfig browserConfig = null;

        if (isBrowser) {
            // === Browser single-turn scout ===
            browserConfig = parseBrowserConfig(task);
            browserSessionId = taskId; // use taskId as browser session key
            try {
                browserService.openSession(browserSessionId, browserConfig);
                phase1Success = browserSingleTurnService.runScoutPhase(
                        taskId, task, browserSessionId, browserConfig,
                        attackContext, goalIds, techniqueIds);
            } catch (Exception e) {
                log.error("Browser scout phase failed for task {}", taskId, e);
                try { browserService.closeSession(browserSessionId); } catch (Exception ignored) {}
                phase1Success = false;
            }
        } else {
            // === HTTP single-turn scout ===
            List<ScanPayload> payloads = buildScoutPayloads(task, request);
            scanPayloadRepository.saveAll(payloads);
            task.setTotalCount(payloads.size());
            taskRepository.save(task);

            log.info("AUTO Phase 1: {} HTTP scout payloads for task {}", payloads.size(), taskId);
            scanExecutorService.executeTask(taskId);
            waitForTaskCompletion(taskId);
            phase1Success = checkPhase1Success(taskId);
        }

        Task refreshed = taskRepository.findById(taskId).orElseThrow();
        if (phase1Success && !forcePhase2) {
            // Browser session cleanup
            if (isBrowser && browserSessionId != null) {
                try { browserService.closeSession(browserSessionId); } catch (Exception ignored) {}
            }
            refreshed.setStatus(Task.TaskStatus.COMPLETED);
            taskRepository.save(refreshed);
            log.info("AUTO Phase 1 succeeded for task {}, no need for Phase 2", taskId);
            return TaskResponse.from(refreshed);
        }
        if (phase1Success && forcePhase2) {
            log.info("DUAL mode: Phase 1 succeeded but forcePhase2=true, continuing to Phase 2 Agent", taskId);
        }

        // -- Phase 2: Agent siege --
        log.info("AUTO Phase 2 launching Agent for task {}", taskId);
        String scoutIntel = buildScoutIntelligence(taskId, refreshed.getScanMode());
        String reconMemory = reconIntelligenceService.loadTargetIntelligenceMemory(
                task.getReconResultId(),
                request.getSelectedSurfaceIds());

        List<AgentSession> sessions = buildAgentSessions(task, request);
        for (AgentSession s : sessions) {
            s.setIntelligenceLog(scoutIntel);
            s.setExternalIntelligence(task.getExternalIntelligence());
            if (task.getReconResultId() != null) {
                reconIntelligenceService.initializeSession(s, task, reconMemory);
                s.setTargetIntelligenceMemory(reconIntelligenceService.appendIntelligence(
                        s.getTargetIntelligenceMemory(), "DUAL_SCOUT", scoutIntel));
            } else {
                s.setTargetIntelligenceMemory(scoutIntel);
                s.setCurrentPhase("BUILD"); // existing AUTO/DUAL behavior
            }
        }
        agentSessionRepository.saveAll(sessions);
        refreshed.setTotalCount(refreshed.getTotalCount() + sessions.size());
        refreshed.setStatus(Task.TaskStatus.RUNNING);
        taskRepository.save(refreshed);

        // Each browser Agent gets an isolated page. They share only the authenticated
        // browser context, preventing concurrent messages from stealing each other's DOM response.
        if (isBrowser && browserSessionId != null && browserConfig != null) {
            for (AgentSession session : sessions) {
                browserService.forkSession(
                        browserSessionId, session.getId(), browserConfig);
                agentSessionExecutor.executeSession(session.getId());
            }
            browserService.closeSession(browserSessionId);
        } else {
            for (AgentSession session : sessions) {
                agentSessionExecutor.executeSession(session.getId());
            }
        }

        TaskResponse resp = TaskResponse.from(refreshed);
        resp.setAgentCount(sessions.size());
        return resp;
    }

    // ---- helpers ----

    private List<ScanPayload> buildScoutPayloads(Task task, CreateTaskRequest request) {
        // generateCount=1 per combination for scout phase
        CreateTaskRequest scoutReq = new CreateTaskRequest();
        scoutReq.setGoalIds(request.getGoalIds());
        scoutReq.setTechniqueIds(request.getTechniqueIds());
        scoutReq.setAttackContext(request.getAttackContext());
        scoutReq.setGenerateCount(1);

        List<ScanPayload> payloads = new ArrayList<>();
        for (String goalId : scoutReq.getGoalIds()) {
            for (String techniqueId : scoutReq.getTechniqueIds()) {
                List<String> generated = payloadGeneratorService.generate(
                        goalId, techniqueId, scoutReq.getAttackContext(), 1);
                for (int i = 0; i < generated.size(); i++) {
                    ScanPayload p = new ScanPayload();
                    p.setTaskId(task.getId());
                    p.setStrategyId("auto_scout");
                    p.setGoal(goalId);
                    p.setTechnique(techniqueId);
                    p.setTemplateName(goalId + "_" + techniqueId + "_" + (i + 1));
                    p.setContent(generated.get(i));
                    p.setStatus(ScanPayload.PayloadStatus.PENDING);
                    payloads.add(p);
                }
            }
        }
        return payloads;
    }

    private void waitForTaskCompletion(Long taskId) {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskRepository.findById(taskId).orElse(null);
            if (t != null && (t.getStatus() == Task.TaskStatus.COMPLETED
                    || t.getStatus() == Task.TaskStatus.FAILED)) {
                return;
            }
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        log.warn("AUTO Phase 1 timed out for task {}", taskId);
    }

    private boolean checkPhase1Success(Long taskId) {
        Task t = taskRepository.findById(taskId).orElse(null);
        return t != null && t.getSuccessCount() > 0;
    }

    private String buildScoutIntelligence(Long taskId, Task.ScanMode scanMode) {
        List<ScanResult> results = scanResultRepository.findAllByTaskId(taskId);
        List<ScanPayload> payloads = scanPayloadRepository.findByTaskId(taskId);
        StringBuilder sb = new StringBuilder();
        sb.append("[SCOUT PHASE] Single-turn payload results:\n");
        for (ScanResult r : results) {
            ScanPayload p = payloads.stream()
                    .filter(x -> x.getId().equals(r.getPayloadId()))
                    .findFirst().orElse(null);
            String goal = p != null ? p.getGoal() : "-";
            String tech = p != null ? p.getTechnique() : "-";
            String evidence = r.getEvidence() != null
                    ? r.getEvidence().substring(0, Math.min(120, r.getEvidence().length()))
                    : "";
            sb.append(String.format("  goal=%s tech=%s verdict=%s evidence=%s\n",
                    goal, tech, r.getVerdict(), evidence));
            if (r.getExtractedText() != null && !r.getExtractedText().isBlank()) {
                String preview = r.getExtractedText().length() > 250
                        ? r.getExtractedText().substring(0, 250) + "..."
                        : r.getExtractedText();
                sb.append("    response: ").append(preview).append("\n");
            }
        }
        sb.append("\nStarting Phase 2 from BUILD stage based on scout intelligence above.");
        return sb.toString();
    }

    private BrowserTargetConfig parseBrowserConfig(Task task) {
        try {
            return objectMapper.readValue(task.getTargetConfig(), BrowserTargetConfig.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse browser config for task " + task.getId(), e);
        }
    }

    private List<AgentSession> buildAgentSessions(Task task, CreateTaskRequest request) {
        List<AgentSession> sessions = new ArrayList<>();
        List<String> goalIds = request.getGoalIds();
        List<String> techniqueIds = request.getTechniqueIds();
        if (techniqueIds == null || techniqueIds.isEmpty()) {
            techniqueIds = new ArrayList<>(payloadGeneratorService.getAvailableTechniqueIds());
        }
        if (goalIds == null || goalIds.isEmpty()) {
            goalIds = new ArrayList<>(payloadGeneratorService.getAvailableGoalIds());
        }

        String techniquesJson;
        try {
            techniquesJson = objectMapper.writeValueAsString(techniqueIds);
        } catch (Exception e) {
            techniquesJson = "[]";
        }

        int sessionIndex = 0;
        for (String goalId : goalIds) {
            for (int i = 0; i < request.getGenerateCount(); i++) {
                sessionIndex++;
                AgentSession session = new AgentSession();
                session.setTaskId(task.getId());
                session.setSessionIndex(sessionIndex);
                session.setGoal(goalId);
                session.setAvailableTechniques(techniquesJson);
                session.setExternalIntelligence(task.getExternalIntelligence());
                session.setReconConfig(task.getReconConfig());
                session.setMaxTurns(task.getMaxTurns() != null ? task.getMaxTurns() : 5);
                session.setRetryCount(task.getRetryCount() != null ? task.getRetryCount() : 0);
                session.setCurrentRetry(0);
                session.setCurrentTurn(0);
                session.setStatus(AgentSession.SessionStatus.PENDING);
                sessions.add(session);
            }
        }
        log.info("Created {} agent sessions for task {} (goals={}, generateCount={})",
                sessions.size(), task.getId(), goalIds, request.getGenerateCount());
        return sessions;
    }
}
