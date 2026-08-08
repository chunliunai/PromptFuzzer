package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.dto.CreateTaskRequest;
import com.promptfuzzer.dto.PageResponse;
import com.promptfuzzer.dto.ScanResultResponse;
import com.promptfuzzer.dto.TaskResponse;
import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.OobTarget;
import com.promptfuzzer.entity.ReconAttackCandidate;
import com.promptfuzzer.entity.ScanPayload;
import com.promptfuzzer.entity.ScanResult;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.repository.AgentSessionRepository;
import com.promptfuzzer.repository.AgentStrategySnapshotRepository;
import com.promptfuzzer.repository.AgentWorkflowTraceRepository;
import com.promptfuzzer.repository.OobTargetRepository;
import com.promptfuzzer.repository.ReconAttackCandidateRepository;
import com.promptfuzzer.repository.ScanPayloadRepository;
import com.promptfuzzer.repository.ScanResultRepository;
import com.promptfuzzer.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final ScanPayloadRepository scanPayloadRepository;
    private final ScanResultRepository scanResultRepository;
    private final AgentSessionRepository agentSessionRepository;
    private final OobTargetRepository oobTargetRepository;
    private final AgentWorkflowTraceRepository agentWorkflowTraceRepository;
    private final AgentStrategySnapshotRepository agentStrategySnapshotRepository;
    private final ReconAttackCandidateRepository reconAttackCandidateRepository;
    private final ScanExecutorService scanExecutorService;
    private final AgentSessionExecutor agentSessionExecutor;
    private final AutoModeService autoModeService;
    private final PayloadGeneratorService payloadGeneratorService;
    private final OobService oobService;
    private final ReconIntelligenceService reconIntelligenceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TaskResponse createTask(CreateTaskRequest request) {
        Task task = new Task();
        task.setName(request.getName());
        task.setScanMode(Task.ScanMode.valueOf(request.getScanMode()));
        task.setRawRequestTemplate(request.getRawRequestTemplate());
        task.setPromptFieldPath(request.getPromptFieldPath());
        task.setAttackContext(request.getAttackContext());
        task.setExternalIntelligence(request.getExternalIntelligence());
        if (request.getReconConfig() != null) {
            try {
                task.setReconConfig(objectMapper.writeValueAsString(request.getReconConfig()));
            } catch (Exception e) {
                log.warn("Failed to serialize reconConfig for task '{}'", request.getName(), e);
            }
        }
        task.setStatus(Task.TaskStatus.PENDING);

        // BROWSER mode: serialize targetConfig JSON to Task
        if (task.getScanMode() == Task.ScanMode.BROWSER && request.getTargetConfig() != null) {
            try {
                task.setTargetConfig(objectMapper.writeValueAsString(request.getTargetConfig()));
            } catch (Exception e) {
                log.error("Failed to serialize targetConfig", e);
            }
        }

        String attackMode = request.getAttackMode() != null ? request.getAttackMode() : "SINGLE_TURN";
        task.setAttackMode(Task.AttackMode.valueOf(attackMode));
        task.setMaxTurns(request.getMaxTurns() != null ? request.getMaxTurns() : 5);
        task.setRetryCount(request.getRetryCount() != null ? request.getRetryCount() : 0);
        task.setReconResultId(request.getReconResultId());
        task.setSupplementalRecon(Boolean.TRUE.equals(request.getSupplementalRecon()));
        List<Long> effectiveSurfaceIds =
                reconIntelligenceService.resolveSelectedSurfaceIds(
                        request.getReconResultId(),
                        request.getSelectedSurfaceIds());
        task.setSelectedSurfaceIds(serializeSelectedSurfaceIds(
                effectiveSurfaceIds.isEmpty()
                        ? request.getSelectedSurfaceIds()
                        : effectiveSurfaceIds));
        validateReconReference(task);
        String reconMemory = reconIntelligenceService.loadTargetIntelligenceMemory(
                task.getReconResultId(),
                effectiveSurfaceIds.isEmpty()
                        ? request.getSelectedSurfaceIds()
                        : effectiveSurfaceIds);
        task = taskRepository.save(task);

        final Long taskId = task.getId();

        if (task.getAttackMode() == Task.AttackMode.AUTO
                || task.getAttackMode() == Task.AttackMode.DUAL) {
            boolean forcePhase2 = task.getAttackMode() == Task.AttackMode.DUAL;
            return autoModeService.executeAutoMode(task, request, forcePhase2);
        }

        if (task.getAttackMode() == Task.AttackMode.AGENT) {
            // === AGENT MODE: create agent sessions ===
            List<AgentSession> sessions = buildAgentSessions(task, request, reconMemory);
            agentSessionRepository.saveAll(sessions);
            task.setTotalCount(sessions.size());
            task.setStatus(Task.TaskStatus.RUNNING);
            taskRepository.save(task);

            // Launch each agent async
            for (AgentSession session : sessions) {
                agentSessionExecutor.executeSession(session.getId());
            }

            return TaskResponse.from(task, sessions.size());
        }

        // === SINGLE_TURN MODE ===
        if (request.getGoalIds() == null || request.getGoalIds().isEmpty()
                || request.getTechniqueIds() == null || request.getTechniqueIds().isEmpty()) {
            throw new IllegalArgumentException(
                    "SINGLE_TURN requires non-empty goalIds and techniqueIds");
        }
        List<ScanPayload> payloads = buildPayloadsWithAI(task, request);
        scanPayloadRepository.saveAll(payloads);

        task.setTotalCount(payloads.size());
        taskRepository.save(task);

        scanExecutorService.executeTask(taskId);

        return TaskResponse.from(task);
    }

    private List<AgentSession> buildAgentSessions(Task task, CreateTaskRequest request,
                                                   String reconMemory) {
        List<AgentSession> sessions = new ArrayList<>();
        List<String> goalIds = request.getGoalIds();
        List<String> techniqueIds = request.getTechniqueIds();

        // Use all available techniques if not specified
        if (techniqueIds == null || techniqueIds.isEmpty()) {
            techniqueIds = new ArrayList<>(payloadGeneratorService.getAvailableTechniqueIds());
        }
        // Use all available goals if not specified (Agent can switch goals, but seed with all)
        if (goalIds == null || goalIds.isEmpty()) {
            goalIds = new ArrayList<>(payloadGeneratorService.getAvailableGoalIds());
        }

        String techniquesJson;
        try {
            techniquesJson = objectMapper.writeValueAsString(techniqueIds);
        } catch (Exception e) {
            log.error("Failed to serialize techniques", e);
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
                reconIntelligenceService.initializeSession(session, task, reconMemory);
                sessions.add(session);
            }
        }
        log.info("Created {} agent sessions for task {} (goals={}, generateCount={})",
                sessions.size(), task.getId(), goalIds, request.getGenerateCount());
        return sessions;
    }

    private void validateReconReference(Task task) {
        if (task.getReconResultId() == null) {
            if (Boolean.TRUE.equals(task.getSupplementalRecon())) {
                throw new IllegalArgumentException(
                        "supplementalRecon requires reconResultId");
            }
            if (task.getSelectedSurfaceIds() != null
                    && !task.getSelectedSurfaceIds().isBlank()) {
                throw new IllegalArgumentException(
                        "selectedSurfaceIds requires reconResultId");
            }
            return;
        }
        if (task.getAttackMode() != Task.AttackMode.AGENT
                && task.getAttackMode() != Task.AttackMode.DUAL) {
            throw new IllegalArgumentException(
                    "reconResultId is supported only for AGENT or DUAL tasks");
        }
    }

    private String serializeSelectedSurfaceIds(List<Long> selectedSurfaceIds) {
        if (selectedSurfaceIds == null || selectedSurfaceIds.isEmpty()) {
            return null;
        }
        Set<Long> normalized = new LinkedHashSet<>();
        for (Long id : selectedSurfaceIds) {
            if (id != null && id > 0) {
                normalized.add(id);
            }
        }
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to serialize selectedSurfaceIds", e);
        }
    }

    private List<ScanPayload> buildPayloadsWithAI(Task task, CreateTaskRequest request) {
        List<ScanPayload> payloads = new ArrayList<>();
        for (String goalId : request.getGoalIds()) {
            OobTarget oobTarget = oobService.isOobGoal(goalId)
                    ? oobService.prepareTaskTarget(task.getId(), goalId)
                    : null;
            String attackContext = buildAttackContextWithOob(request.getAttackContext(), oobTarget);
            for (String techniqueId : request.getTechniqueIds()) {
                List<String> generated = payloadGeneratorService.generate(
                        goalId, techniqueId, attackContext, request.getGenerateCount());

                if (generated.isEmpty()) {
                    log.warn("No payloads generated for goal={} technique={}", goalId, techniqueId);
                    continue;
                }

                for (int i = 0; i < generated.size(); i++) {
                    ScanPayload payload = new ScanPayload();
                    payload.setTaskId(task.getId());
                    payload.setStrategyId("ai_generated");
                    payload.setGoal(goalId);
                    payload.setTechnique(techniqueId);
                    payload.setTemplateName(goalId + "_" + techniqueId + "_" + (i + 1));
                    payload.setContent(oobTarget != null
                            ? oobService.applyOobVariables(generated.get(i), oobTarget)
                            : generated.get(i));
                    payload.setStatus(ScanPayload.PayloadStatus.PENDING);
                    payloads.add(payload);
                }
                log.info("Generated {} payloads for goal={} technique={}", generated.size(), goalId, techniqueId);
            }
        }
        return payloads;
    }

    private String buildAttackContextWithOob(String attackContext, OobTarget target) {
        if (target == null) {
            return attackContext;
        }
        String base = attackContext != null ? attackContext : "";
        return base + "\n\n"
                + "本次 SSRF/OOB 验证使用任务级外部观测 URL: " + target.getOobUrl() + "\n"
                + "生成 payload 时必须把该 URL 放入需要目标读取、检查、总结、导入或访问的远程资源位置。"
                + "也可以使用占位符 {{OOB_URL}}，系统会自动替换为该 URL。"
                + "成功标志不是目标 AI 声称访问，而是外部 OOB/DNSLog 是否捕获到请求。";
    }

    public List<TaskResponse> listTasks() {
        return taskRepository.findAll().stream()
                .map(TaskResponse::from)
                .collect(Collectors.toList());
    }

    public TaskResponse getTask(Long id) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Task not found: " + id));
        TaskResponse r = TaskResponse.from(task);

        if (task.getAttackMode() == Task.AttackMode.AGENT
                || task.getAttackMode() == Task.AttackMode.AUTO
                || task.getAttackMode() == Task.AttackMode.DUAL) {
            List<AgentSession> agents = agentSessionRepository
                    .findByTaskIdOrderBySessionIndexAsc(id);
            int successCount = (int) agents.stream()
                    .filter(s -> s.getStatus() == AgentSession.SessionStatus.SUCCESS).count();
            int blockedCount = (int) agents.stream()
                    .filter(s -> s.getStatus() == AgentSession.SessionStatus.BLOCKED).count();
            r.setAgentCount(agents.size());
            r.setAgentSuccessCount(successCount);
            r.setAgentBlockedCount(blockedCount);
        }
        return r;
    }

    public PageResponse<ScanResultResponse> getTaskResults(Long taskId, int page, int size) {
        Page<ScanResult> resultPage = scanResultRepository.findByTaskId(taskId, PageRequest.of(page, size));
        List<Long> payloadIds = resultPage.getContent().stream()
                .map(ScanResult::getPayloadId).collect(Collectors.toList());
        List<ScanPayload> payloads = scanPayloadRepository.findAllById(payloadIds);

        List<ScanResultResponse> items = resultPage.getContent().stream().map(result -> {
            ScanPayload payload = payloads.stream()
                    .filter(p -> p.getId().equals(result.getPayloadId()))
                    .findFirst().orElse(null);
            return ScanResultResponse.from(result, payload);
        }).collect(Collectors.toList());

        return PageResponse.of(resultPage.getTotalElements(), page, size, items);
    }

    @Transactional
    public void deleteTask(Long id) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));
        if (task.getStatus() == Task.TaskStatus.PENDING
                || task.getStatus() == Task.TaskStatus.RUNNING) {
            throw new IllegalArgumentException("Running task cannot be deleted: " + id);
        }

        List<ReconAttackCandidate> candidates =
                reconAttackCandidateRepository.findByTaskId(id);
        candidates.forEach(candidate -> {
            candidate.setTaskId(null);
            candidate.setStatus(ReconAttackCandidate.CandidateStatus.DRAFT);
        });
        if (!candidates.isEmpty()) {
            reconAttackCandidateRepository.saveAll(candidates);
        }

        agentWorkflowTraceRepository.deleteByTaskId(id);
        agentStrategySnapshotRepository.deleteByTaskId(id);
        agentSessionRepository.deleteByTaskId(id);
        scanResultRepository.deleteByTaskId(id);
        scanPayloadRepository.deleteByTaskId(id);
        oobTargetRepository.deleteByTaskId(id);
        taskRepository.delete(task);
    }
}
