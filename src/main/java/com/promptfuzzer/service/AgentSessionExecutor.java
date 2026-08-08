package com.promptfuzzer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptfuzzer.config.BrowserTargetConfig;
import com.promptfuzzer.dto.ReconConfig;
import com.promptfuzzer.agent.workflow.AgentTurnBudget;
import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowEngine;
import com.promptfuzzer.agent.workflow.AgentWorkflowRuntime;
import com.promptfuzzer.agent.workflow.AgentWorkflowSessionRunner;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.agent.workflow.AgentWorkflowTurnOutput;
import com.promptfuzzer.agent.workflow.AgentWorkflowTurnRequest;
import com.promptfuzzer.agent.workflow.WorkflowNodeResult;
import com.promptfuzzer.agent.workflow.WorkflowRoute;
import com.promptfuzzer.agent.workflow.node.CompositeReviewNode;
import com.promptfuzzer.agent.workflow.node.IndependentReconNode;
import com.promptfuzzer.agent.workflow.node.StrategyPlanNode;
import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.AgentStrategySnapshot;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.repository.AgentSessionRepository;
import com.promptfuzzer.repository.AgentStrategySnapshotRepository;
import com.promptfuzzer.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AgentSessionExecutor {

    private final AgentWorkflowEngine workflowEngine;

    @Async("scanExecutor")
    public void executeSession(Long sessionId) {
        executeSession(sessionId, null);
    }

    /**
     * Execute an Agent session, optionally reusing a browser session created
     * by the AUTO/DUAL scout phase.
     */
    @Async("scanExecutor")
    public void executeSession(Long sessionId, Long existingBrowserSessionId) {
        workflowEngine.executeSession(sessionId, existingBrowserSessionId);
    }
}

@Slf4j
@Component
@RequiredArgsConstructor
class AgentWorkflowSessionRunnerImpl implements AgentWorkflowSessionRunner {

    private final AgentSessionRepository agentSessionRepository;
    private final TaskRepository taskRepository;
    private final AgentPlannerService plannerService;
    private final BrowserService browserService;
    private final AgentStrategySnapshotRepository strategySnapshotRepository;
    private final AgentWorkflowTraceRecorder workflowTraceRecorder;
    private final AgentWorkflowRuntime workflowRuntime;
    private final StrategyPlanNode strategyPlanNode;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String[] PHASES = {"RECON", "BUILD", "ATTACK"};

    @Override
    public void runSession(Long sessionId, Long existingBrowserSessionId) {
        AgentSession session = agentSessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            log.error("AgentSession not found: {}", sessionId);
            return;
        }

        Task task = taskRepository.findById(session.getTaskId()).orElse(null);
        if (task == null) {
            log.error("Task not found for AgentSession {}", sessionId);
            session.setStatus(AgentSession.SessionStatus.ERROR);
            agentSessionRepository.save(session);
            return;
        }

        session.setStatus(AgentSession.SessionStatus.RUNNING);
        session.setCurrentTurn(0);
        agentSessionRepository.save(session);

        List<String> availableTechniques = parseTechniques(session.getAvailableTechniques());
        String goal = session.getGoal();
        String attackContext = task.getAttackContext();
        int maxTurns = session.getMaxTurns();

        boolean isBrowser = task.getScanMode() == Task.ScanMode.BROWSER;
        Long browserSid = (existingBrowserSessionId != null) ? existingBrowserSessionId : sessionId;
        AgentWorkflowContext workflowContext = AgentWorkflowContext.builder()
                .task(task)
                .session(session)
                .availableTechniques(availableTechniques)
                .browserMode(isBrowser)
                .build();
        BrowserTargetConfig browserConfig = null;
        boolean browserReused = false;
        if (isBrowser) {
            // Check if Phase 1 already opened a browser session we can reuse
            if (existingBrowserSessionId != null && browserService.hasSession(existingBrowserSessionId)) {
                log.info("AgentSession {} reusing existing browser session {}", sessionId, existingBrowserSessionId);
                browserReused = true;
            } else {
                try {
                    browserConfig = objectMapper.readValue(task.getTargetConfig(), BrowserTargetConfig.class);
                    browserService.openSession(browserSid, browserConfig);
                    log.info("AgentSession {} browser session opened for {}", sessionId,
                            browserConfig.getChatUrl());
                } catch (Exception e) {
                    log.error("AgentSession {} failed to open browser", sessionId, e);
                    session.setStatus(AgentSession.SessionStatus.ERROR);
                    agentSessionRepository.save(session);
                    return;
                }
            }
        }

        String currentPhase = session.getCurrentPhase() != null ? session.getCurrentPhase() : "RECON";
        String intelligenceLog = session.getIntelligenceLog() != null ? session.getIntelligenceLog() : "";
        String strategyPlan = session.getStrategyPlan() != null ? session.getStrategyPlan() : "";
        String targetMemory = session.getTargetIntelligenceMemory();
        String buildMemory = session.getBuildMemory();
        String attackSignals = session.getAttackSignals();
        int targetChatIndex = session.getTargetChatIndex() != null ? session.getTargetChatIndex() : 0;
        List<Map<String, Object>> turnRecords = new ArrayList<>();
        List<Map<String, Object>> allTurnRecords = new ArrayList<>();

        if (session.getExternalIntelligence() == null || session.getExternalIntelligence().isBlank()) {
            session.setExternalIntelligence(task.getExternalIntelligence());
        }
        ReconConfig reconConfig = resolveReconConfig(session, task, attackContext);
        if (targetMemory == null || targetMemory.isBlank()) {
            long traceStart = System.currentTimeMillis();
            try {
                targetMemory = plannerService.preprocessIntelligence(
                        attackContext, session.getExternalIntelligence(), goal, intelligenceLog);
                session.setTargetIntelligenceMemory(targetMemory);
                workflowTraceRecorder.success(task, session, "INITIAL_INTEL", currentPhase,
                        session.getCurrentRetry(), session.getCurrentTurn(), targetChatIndex,
                        traceMap("goal", goal,
                                "hasExternalIntelligence", session.getExternalIntelligence() != null
                                        && !session.getExternalIntelligence().isBlank(),
                                "intelligenceLogChars", intelligenceLog.length()),
                        traceMap("targetMemoryChars", targetMemory != null ? targetMemory.length() : 0),
                        null, null, traceStart);
                log.info("AgentSession {} initial target memory generated ({} chars)",
                        sessionId, targetMemory.length());
            } catch (Exception e) {
                workflowTraceRecorder.error(task, session, "INITIAL_INTEL", currentPhase,
                        session.getCurrentRetry(), session.getCurrentTurn(), targetChatIndex,
                        traceMap("goal", goal,
                                "hasExternalIntelligence", session.getExternalIntelligence() != null
                                        && !session.getExternalIntelligence().isBlank()),
                        null, null, null, e, traceStart);
                log.warn("AgentSession {} initial target memory generation failed: {}",
                        sessionId, e.getMessage());
                targetMemory = fallbackInitialMemory(attackContext, session.getExternalIntelligence(), intelligenceLog);
                session.setTargetIntelligenceMemory(targetMemory);
            }
        }

        if (shouldRunIndependentRecon(session, task, currentPhase, reconConfig)) {
            long traceStart = System.currentTimeMillis();
            try {
                String previousStrategyPlan = strategyPlan;
                WorkflowNodeResult<IndependentReconNode.IndependentReconOutput> reconResult =
                        workflowRuntime.executeIndependentRecon(
                                workflowContext,
                                workflowState(session, task, "RECON", 0, 0, targetChatIndex,
                                        attackContext, targetMemory, buildMemory, attackSignals, strategyPlan),
                                browserSid,
                                reconConfig);
                IndependentReconNode.IndependentReconOutput output = reconResult.getOutput();
                targetMemory = output.getTargetMemory();
                strategyPlan = output.getStrategyPlan();
                targetChatIndex = output.getTargetChatIndex();
                List<Map<String, Object>> reconTurnRecords = output.getTurnRecords();
                currentPhase = "BUILD";
                allTurnRecords.addAll(reconTurnRecords);
                session.setCurrentPhase(currentPhase);
                session.setTargetIntelligenceMemory(targetMemory);
                session.setStrategyPlan(strategyPlan);
                session.setTargetChatIndex(targetChatIndex);
                session.setReconCompleted(true);
                recordStrategySnapshot(session, strategyPlan, previousStrategyPlan,
                        "POST_INDEPENDENT_RECON", currentPhase, 0, 0,
                        targetChatIndex,
                        "Independent RECON completed; generate first unique BUILD/ATTACK strategy",
                        buildRecentPayloads(allTurnRecords, 5));
                persistConversationHistory(session, allTurnRecords, Collections.emptyList());
                agentSessionRepository.save(session);
                log.info("AgentSession {} independent RECON completed, records={}, nextPhase=BUILD",
                        sessionId, reconTurnRecords.size());
            } catch (Exception e) {
                log.warn("AgentSession {} independent RECON failed, continuing with phase loop: {}",
                        sessionId, e.getMessage());
            }
        }

        // ---- Retry loop ----
        try {
        List<Integer> retryIndexes = workflowRuntime.retryIndexes(session.getRetryCount());
        retryLoop:
        for (int currentRetry : retryIndexes) {
            session.setCurrentRetry(currentRetry);
            session.setStatus(AgentSession.SessionStatus.RUNNING);

            if (currentRetry > 0) {
                // Mark new attempt
                intelligenceLog += "\n[SYSTEM] 第 " + (currentRetry + 1) + " 次尝试开始，AI 上下文已重置";
                String previousStrategyPlan = strategyPlan;
                String recentPayloads = buildRecentPayloads(allTurnRecords, 5);
                String replanReason = "Retry " + currentRetry
                        + " started after previous attempt failed or stalled";
                try {
                    WorkflowNodeResult<com.promptfuzzer.agent.workflow.node.RetryPreparationNode.RetryPreparationOutput>
                            retryResult = workflowRuntime.prepareRetry(
                            workflowContext,
                            workflowState(session, task, currentPhase, currentRetry, 0,
                                    targetChatIndex, attackContext, targetMemory, buildMemory,
                                    attackSignals, strategyPlan),
                            browserSid, availableTechniques, intelligenceLog,
                            recentPayloads, replanReason);
                    strategyPlan = retryResult.getOutput().getStrategyPlan();
                    targetChatIndex = retryResult.getOutput().getTargetChatIndex();
                    session.setTargetChatIndex(targetChatIndex);
                } catch (Exception e) {
                    log.warn("AgentSession {} workflow retry preparation failed for retry {}",
                            sessionId, currentRetry, e);
                }
                recordStrategySnapshot(session, strategyPlan, previousStrategyPlan,
                        "RETRY_REPLAN", currentPhase, currentRetry, 0,
                        targetChatIndex, replanReason, recentPayloads);
                log.info("AgentSession {} retry {} strategy re-planned ({} chars)",
                        sessionId, currentRetry, strategyPlan != null ? strategyPlan.length() : 0);
            } else {
                if (!"RECON".equals(currentPhase) && (strategyPlan == null || strategyPlan.isBlank())) {
                    try {
                        String recentPayloads = buildRecentPayloads(allTurnRecords, 5);
                        String replanReason = "Initial BUILD/ATTACK strategy generation";
                        WorkflowNodeResult<String> planResult = strategyPlanNode.execute(workflowContext,
                                workflowState(session, task, currentPhase, currentRetry, 0, targetChatIndex,
                                        attackContext, targetMemory, buildMemory, attackSignals, strategyPlan),
                                "INITIAL_PLAN", availableTechniques,
                                AgentTurnBudget.beforeFirstTurn(maxTurns).remainingTurnsIncludingCurrent(),
                                intelligenceLog, null, recentPayloads, replanReason);
                        strategyPlan = planResult.getOutput();
                        recordStrategySnapshot(session, strategyPlan, null,
                                "INITIAL_PLAN", currentPhase, currentRetry, 0,
                                targetChatIndex, replanReason, recentPayloads);
                        log.info("AgentSession {} strategy plan generated ({} chars)",
                                sessionId, strategyPlan.length());
                    } catch (Exception e) {
                        log.warn("AgentSession {} strategy plan failed", sessionId, e);
                    }
                }
            }

            session.setCurrentPhase(currentPhase);
            session.setIntelligenceLog(intelligenceLog);
            session.setStrategyPlan(strategyPlan);
            session.setTargetIntelligenceMemory(targetMemory);
            session.setBuildMemory(buildMemory);
            session.setAttackSignals(attackSignals);
            session.setTargetChatIndex(targetChatIndex);
            agentSessionRepository.save(session);

            turnRecords = new ArrayList<>();
            int turnsInPhase = 0;
            int currentTurn = 0;

            log.info("AgentSession {} (task {}) attempt {}/{} started. goal={}, weapons={}, maxTurns={}",
                    sessionId, task.getId(), currentRetry + 1, session.getRetryCount() + 1,
                    goal, availableTechniques, maxTurns);

            List<Integer> turnIndexes = workflowRuntime.turnIndexes(maxTurns);
            turnLoop:
            for (int turnNum : turnIndexes) {
                turnsInPhase++;
                currentTurn = turnNum;
                session.setCurrentTurn(turnNum);
                AgentTurnBudget turnBudget = AgentTurnBudget.forExecutingTurn(turnNum, maxTurns);

                Map<String, Object> turnRecord = new LinkedHashMap<>();
                turnRecord.put("turn", turnNum);
                turnRecord.put("phase", currentPhase);
                turnRecord.put("targetChatIndex", targetChatIndex);

                // --- Step 0: Strategy health check (before expensive analyze) ---
                if (turnRecords.size() >= 3) {
                    Map<String, Object> t1 = turnRecords.get(turnRecords.size() - 3);
                    Map<String, Object> t2 = turnRecords.get(turnRecords.size() - 2);
                    Map<String, Object> t3 = turnRecords.get(turnRecords.size() - 1);
                    String r1 = getExtractedText(t1);
                    String r2 = getExtractedText(t2);
                    String r3 = getExtractedText(t3);
                    String p1 = stringValue(t1.get("message"));
                    String p2 = stringValue(t2.get("message"));
                    String p3 = stringValue(t3.get("message"));
                    String weaponHistory = buildWeaponHistory(turnRecords);

                    long traceStart = System.currentTimeMillis();
                    try {
                        AgentWorkflowState currentWorkflowState = workflowState(
                                session, task, currentPhase, currentRetry, turnNum, targetChatIndex,
                                attackContext, targetMemory, buildMemory, attackSignals, strategyPlan);
                        AgentPlannerService.StrategyCheckResult check =
                                workflowRuntime.checkStrategy(workflowContext, currentWorkflowState,
                                        p1, p2, p3, r1, r2, r3, weaponHistory);
                        log.info("AgentSession {} turn {} strategyCheck: status={} routeHealth={} progress={} reason={}",
                                sessionId, turnNum, check.getStatus(), check.getRouteHealth(),
                                check.getGoalProgress(), check.getReason());

                        WorkflowNodeResult<Void> strategyRoute =
                                workflowRuntime.routeAfterStrategyCheck(check);
                        boolean strategyDead = strategyRoute.getRoute() == WorkflowRoute.STOP;
                        if (strategyDead) {
                            log.info("AgentSession {} strategyCheck DEAD, breaking turn loop", sessionId);
                            turnRecord.put("analysis", "[SKIP] 策略健康检查判定 DEAD: " + check.getReason());
                            turnRecord.put("technique", "none");
                            turnRecord.put("message", "");
                            turnRecord.put("verdict", null);
                            turnRecord.put("evidence", "Strategy DEAD: " + check.getReason());
                            turnRecords.add(turnRecord);
                            session.setStatus(AgentSession.SessionStatus.BLOCKED);
                            break turnLoop;
                        }

                        boolean strategyCheckReplan =
                                strategyRoute.getRoute() == WorkflowRoute.REPLAN;
                        if (strategyCheckReplan) {
                            log.info("AgentSession {} strategyCheck STALE, invalidating strategy", sessionId);
                            try {
                                String previousStrategyPlan = strategyPlan;
                                String recentPayloads = buildRecentPayloads(turnRecords, 5);
                                String replanReason = "StrategyCheck requires replan: " + check.getReason()
                                        + "; evidenceGap=" + check.getEvidenceGap()
                                        + "; requiredChange=" + check.getRequiredChange()
                                        + "; routeInvalidationEvidence=" + check.getRouteInvalidationEvidence();
                                strategyPlan = workflowRuntime.replan(workflowContext, currentWorkflowState,
                                        "STRATEGY_CHECK_REPLAN", availableTechniques,
                                        turnBudget.remainingTurnsIncludingCurrent(), session.getIntelligenceLog(),
                                        previousStrategyPlan, recentPayloads, replanReason);
                                session.setStrategyPlan(strategyPlan);
                                recordStrategySnapshot(session, strategyPlan, previousStrategyPlan,
                                        "STRATEGY_CHECK_REPLAN", currentPhase, currentRetry,
                                        turnNum, targetChatIndex, replanReason, recentPayloads);
                                log.info("AgentSession {} strategy re-planned via STALE ({} chars)",
                                        sessionId, strategyPlan.length());
                            } catch (Exception e) {
                                log.warn("AgentSession {} STALE replan failed", sessionId, e);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("AgentSession {} strategyCheck failed, continuing: {}",
                                sessionId, e.getMessage());
                    }
                }

                String conversationJson = objectMapper.writeValueAsString(turnRecords);
                WorkflowNodeResult<AgentWorkflowTurnOutput> turnResult = workflowRuntime.executeTurn(
                            AgentWorkflowTurnRequest.builder()
                                    .context(workflowContext)
                                    .state(workflowState(session, task, currentPhase, currentRetry, turnNum,
                                            targetChatIndex, attackContext, targetMemory, buildMemory,
                                            attackSignals, strategyPlan))
                                    .conversationJson(conversationJson)
                                    .intelligenceLog(intelligenceLog)
                                    .turnRecords(turnRecords)
                                    .browserSessionId(browserSid)
                                    .rawRequestTemplate(task.getRawRequestTemplate())
                                    .build());
                AgentWorkflowTurnOutput turnOutput = turnResult.getOutput();
                AgentPlannerService.StrategyAnalysis analysis = turnOutput.getAnalysis();
                turnRecord = turnOutput.getTurnRecord();
                targetChatIndex = turnOutput.getTargetChatIndex();
                attackSignals = turnOutput.getAttackSignals();
                session.setTargetChatIndex(targetChatIndex);
                session.setAttackSignals(attackSignals);

                if (analysis != null) {
                        String actionType = stringValue(turnRecord.get("action"));
                        if ("NEW_CHAT".equals(actionType)) {
                            accumulateObservations(session, currentPhase, turnNum, "重置了对话上下文");
                        } else if ("READ_PAGE".equals(actionType)) {
                            String pageContent = stringValue(turnRecord.get("extractedText"));
                            accumulateObservations(session, currentPhase, turnNum,
                                    "页面内容: " + (pageContent != null
                                            ? pageContent.substring(0, Math.min(200, pageContent.length()))
                                            : "(空)"));
                        }
                        accumulateObservations(session, currentPhase, turnNum, analysis.getObservations());
                }

                if (turnResult.getRoute() == WorkflowRoute.SUCCESS) {
                        session.setStatus(AgentSession.SessionStatus.SUCCESS);
                        session.setFinalVerdict(AgentSession.Verdict.SUCCESS);
                        session.setFinalEvidence(turnResult.getEvidence());
                        turnRecords.add(turnRecord);
                        allTurnRecords.addAll(turnRecords);
                        break retryLoop;
                }
                if (turnResult.getRoute() == WorkflowRoute.STOP) {
                        turnRecords.add(turnRecord);
                        session.setStatus(AgentSession.SessionStatus.BLOCKED);
                        break turnLoop;
                }
                if (turnResult.getRoute() == WorkflowRoute.ERROR) {
                        turnRecords.add(turnRecord);
                        break turnLoop;
                }
                turnRecords.add(turnRecord);

                int phaseMaxTurns = getPhaseMaxTurns(strategyPlan, currentPhase);
                WorkflowNodeResult<Void> postTurnRoute = workflowRuntime.routeAfterTurn(
                        analysis, turnBudget, currentPhase, turnsInPhase, phaseMaxTurns,
                        stringValue(turnRecord.get("extractedText")));
                boolean terminalResponse = postTurnRoute.getRoute() == WorkflowRoute.STOP;
                if (terminalResponse) {
                    String reason = "目标会话已返回终止性回复或空响应，结束当前 attempt，避免继续消耗无效轮次";
                    log.info("AgentSession {} turn {} terminal response detected in phase {}",
                            sessionId, turnNum, currentPhase);
                    attackSignals = appendMemorySection(attackSignals,
                            "Terminal Response " + currentPhase + " T" + turnNum, reason);
                    session.setAttackSignals(attackSignals);
                    session.setStatus(AgentSession.SessionStatus.BLOCKED);
                    break turnLoop;
                }

                // --- Step 6: Phase transition / strategy invalidation ---
                // Hard push: force phase advance if stuck too long in current phase
                log.debug("AgentSession {} turn {} phase={} turnsInPhase={}/{} phaseTransition={}",
                        sessionId, turnNum, currentPhase, turnsInPhase, phaseMaxTurns,
                        analysis.isPhaseTransition());
                boolean hardPhaseAdvance =
                        Boolean.TRUE.equals(postTurnRoute.getStateUpdates().get("hardPhaseAdvance"));
                if (hardPhaseAdvance) {
                    log.info("AgentSession {} phase HARD-ADVANCED at turn {} ({} turns in {}, max {})",
                            sessionId, turnNum, turnsInPhase, currentPhase, phaseMaxTurns);
                    analysis.setPhaseTransition(true);
                }

                boolean plannerReplan = postTurnRoute.getRoute() == WorkflowRoute.REPLAN;
                if (plannerReplan) {
                    log.info("AgentSession {} strategy invalidated at turn {}, re-planning", sessionId, turnNum);
                    long replanTraceStart = System.currentTimeMillis();
                    try {
                        String previousStrategyPlan = strategyPlan;
                        String recentPayloads = buildRecentPayloads(turnRecords, 5);
                        String replanReason = "Planner analysis invalidated current strategy at turn "
                                + turnNum + ": " + analysis.getObservations();
                        AgentWorkflowState currentWorkflowState = workflowState(
                                session, task, currentPhase, currentRetry, turnNum, targetChatIndex,
                                attackContext, targetMemory, buildMemory, attackSignals, strategyPlan);
                        strategyPlan = workflowRuntime.replan(workflowContext, currentWorkflowState,
                                "PLANNER_INVALIDATED_REPLAN", availableTechniques,
                                turnBudget.remainingTurnsAfterCurrent(), session.getIntelligenceLog(),
                                previousStrategyPlan, recentPayloads, replanReason);
                        session.setStrategyPlan(strategyPlan);
                        recordStrategySnapshot(session, strategyPlan, previousStrategyPlan,
                                "PLANNER_INVALIDATED_REPLAN", currentPhase, currentRetry,
                                turnNum, targetChatIndex, replanReason, recentPayloads);
                        log.info("AgentSession {} strategy re-planned ({} chars)", sessionId, strategyPlan.length());
                    } catch (Exception e) {
                        log.warn("AgentSession {} strategy re-plan failed", sessionId, e);
                    }
                } else if (analysis.isStrategyInvalidated()) {
                    workflowTraceRecorder.skipped(task, session, "REPLAN", currentPhase,
                            currentRetry, turnNum, targetChatIndex,
                            traceMap("trigger", "PLANNER_INVALIDATED_REPLAN",
                                    "remainingTurnsAfterCurrent", turnBudget.remainingTurnsAfterCurrent()),
                            "当前轮已是最后一轮，跳过仅供下一轮使用的策略重规划",
                            System.currentTimeMillis());
                    log.info("AgentSession {} turn {} is final turn; skipping post-turn replan",
                            sessionId, turnNum);
                }

                if (analysis.isPhaseTransition()) {
                    long phaseTraceStart = System.currentTimeMillis();
                    String previousPhase = currentPhase;
                    if ("RECON".equals(previousPhase)) {
                        targetMemory = summarizeReconAndPlanSafe(session, task, isBrowser,
                                attackContext, goal, availableTechniques, maxTurns,
                                turnRecords, targetMemory);
                        strategyPlan = session.getStrategyPlan();
                        if (isBrowser) {
                            targetChatIndex = newChatBeforeBuildSafe(session, browserSid, targetChatIndex);
                        }
                    } else if ("BUILD".equals(previousPhase)) {
                        buildMemory = summarizeBuildSafe(session, attackContext, goal, targetMemory,
                                strategyPlan, turnRecords);
                    }
                    AgentWorkflowState currentWorkflowState = workflowState(
                            session, task, previousPhase, currentRetry, turnNum, targetChatIndex,
                            attackContext, targetMemory, buildMemory, attackSignals, strategyPlan);
                    currentPhase = workflowRuntime.transitionPhase(workflowContext, currentWorkflowState,
                            turnsInPhase, analysis.getObservations(), targetMemory, buildMemory, strategyPlan);
                    turnsInPhase = 0;
                    session.setCurrentPhase(currentPhase);
                    log.info("AgentSession {} phase advanced to {} at turn {}",
                            sessionId, currentPhase, turnNum);
                }


                session.setIntelligenceLog(session.getIntelligenceLog());
                session.setTargetIntelligenceMemory(targetMemory);
                session.setBuildMemory(buildMemory);
                session.setAttackSignals(attackSignals);
                session.setTargetChatIndex(targetChatIndex);
                persistConversationHistory(session, allTurnRecords, turnRecords);
                agentSessionRepository.save(session);

            } // end while

            // Post-loop for current attempt
            allTurnRecords.addAll(turnRecords);

            if (session.getStatus() == AgentSession.SessionStatus.SUCCESS) {
                break retryLoop;
            }

            // Mark as BLOCKED if still RUNNING
            if (session.getStatus() == AgentSession.SessionStatus.RUNNING) {
                session.setStatus(AgentSession.SessionStatus.BLOCKED);
                if (session.getFinalVerdict() == null) {
                    session.setFinalVerdict(AgentSession.Verdict.FAIL);
                }
            }

            // --- Per-attempt composite review: check all turns before retrying ---
            // Rationale: Agent may have succeeded in RECON/BUILD phase but didn't know
            // because Judge only runs in ATTACK. Scan accumulated turns before giving up.
            if (session.getStatus() == AgentSession.SessionStatus.BLOCKED
                    && session.getFinalVerdict() != AgentSession.Verdict.SUCCESS) {
                log.info("AgentSession {} attempt {} BLOCKED, running per-attempt composite review over {} turns...",
                        sessionId, currentRetry, turnRecords.size());
                try {
                    AgentWorkflowState currentWorkflowState = workflowState(
                            session, task, currentPhase, currentRetry, currentTurn,
                            targetChatIndex, attackContext, targetMemory, buildMemory,
                            attackSignals, strategyPlan);
                    CompositeReviewNode.ReviewResult reviewResult = workflowRuntime.compositeReview(
                            workflowContext, currentWorkflowState, turnRecords, "attempt");
                    if (!reviewResult.isEmpty()) {
                        JudgeService.JudgeResult result = reviewResult.getJudgeResult();
                        log.info("AgentSession {} per-attempt composite review: verdict={}",
                                sessionId, result.getVerdict());
                        AgentSession.Verdict verdict = toAgentVerdict(result.getVerdict());
                        session.setFinalVerdict(verdict);
                        session.setFinalEvidence("(per-attempt composite) " + result.getEvidence());
                        if ("SUCCESS".equals(result.getVerdict())) {
                            session.setStatus(AgentSession.SessionStatus.SUCCESS);
                            log.info("AgentSession {} per-attempt composite review FOUND SUCCESS — skipping retry!",
                                    sessionId);
                            break retryLoop;
                        }
                    }
                } catch (Exception e) {
                    log.warn("AgentSession {} per-attempt composite review failed: {}",
                            sessionId, e.getMessage());
                }
            }

            if (currentRetry >= session.getRetryCount()) {
                log.info("AgentSession {} exhausted all {} retries, giving up",
                        sessionId, session.getRetryCount() + 1);
                break retryLoop;
            }
            log.info("AgentSession {} attempt {} failed, retrying ({}/{})...",
                    sessionId, currentRetry, currentRetry + 1, session.getRetryCount());

        } // end retryLoop

        } catch (Exception e) {
            log.error("AgentSession {} fatal error", sessionId, e);
            session.setStatus(AgentSession.SessionStatus.ERROR);
        } finally {
            // Clean up browser session (only if we created it, not reused from Phase 1)
            if (isBrowser && !browserReused) {
                try {
                    browserService.closeSession(browserSid);
                    log.info("AgentSession {} browser session cleaned up", sessionId);
                } catch (Exception e) {
                    log.warn("AgentSession {} browser cleanup failed", sessionId, e);
                }
            }
        }

        // ---- Composite review: if blocked, judge all accumulated AI responses ----
        if (session.getStatus() == AgentSession.SessionStatus.BLOCKED
                && session.getFinalVerdict() != AgentSession.Verdict.SUCCESS) {
            log.info("AgentSession {} BLOCKED, running composite review over {} turns...",
                    sessionId, allTurnRecords.size());
            try {
                AgentWorkflowState currentWorkflowState = workflowState(
                        session, task, session.getCurrentPhase(), session.getCurrentRetry(),
                        session.getCurrentTurn(), session.getTargetChatIndex(), attackContext,
                        targetMemory, buildMemory, attackSignals, strategyPlan);
                CompositeReviewNode.ReviewResult reviewResult = workflowRuntime.compositeReview(
                        workflowContext, currentWorkflowState, allTurnRecords, "session");
                if (!reviewResult.isEmpty()) {
                    JudgeService.JudgeResult result = reviewResult.getJudgeResult();
                    log.info("AgentSession {} composite review: verdict={}, evidence={}",
                            sessionId, result.getVerdict(), result.getEvidence());
                    AgentSession.Verdict verdict = toAgentVerdict(result.getVerdict());
                    session.setFinalVerdict(verdict);
                    session.setFinalEvidence("(composite) " + result.getEvidence());
                    if ("SUCCESS".equals(result.getVerdict())) {
                        session.setStatus(AgentSession.SessionStatus.SUCCESS);
                        log.info("AgentSession {} composite review FOUND SUCCESS!", sessionId);
                    }
                }
            } catch (Exception e) {
                log.warn("AgentSession {} composite review failed: {}", sessionId, e.getMessage());
            }
        }

        workflowRuntime.finalSync(workflowContext,
                workflowState(session, task, session.getCurrentPhase(),
                        session.getCurrentRetry(), session.getCurrentTurn(),
                        session.getTargetChatIndex(), attackContext, targetMemory,
                        buildMemory, attackSignals, strategyPlan),
                allTurnRecords);

        log.info("AgentSession {} finished. status={}, verdict={}, phase={}, total turns={}",
                sessionId, session.getStatus(), session.getFinalVerdict(),
                session.getCurrentPhase(), allTurnRecords.size());
    }

    private AgentSession.Verdict toAgentVerdict(String verdict) {
        try {
            return AgentSession.Verdict.valueOf(verdict);
        } catch (Exception e) {
            return AgentSession.Verdict.UNCERTAIN;
        }
    }

    private boolean shouldRunIndependentRecon(AgentSession session, Task task, String currentPhase,
                                              ReconConfig reconConfig) {
        if (task.getAttackMode() == Task.AttackMode.SINGLE_TURN) return false;
        if (!"RECON".equals(currentPhase)) return false;
        if (Boolean.TRUE.equals(session.getReconCompleted())) return false;
        if (task.getReconResultId() != null) {
            return Boolean.TRUE.equals(task.getSupplementalRecon());
        }
        return reconConfig == null || reconConfig.getEnabled() == null || reconConfig.getEnabled();
    }

    private ReconConfig resolveReconConfig(AgentSession session, Task task, String attackContext) {
        ReconConfig config = null;
        String raw = session.getReconConfig();
        if ((raw == null || raw.isBlank()) && task.getReconConfig() != null) {
            raw = task.getReconConfig();
            session.setReconConfig(raw);
        }
        if (raw != null && !raw.isBlank()) {
            try {
                config = objectMapper.readValue(raw, ReconConfig.class);
            } catch (Exception e) {
                log.warn("AgentSession {} failed to parse reconConfig, using defaults: {}",
                        session.getId(), e.getMessage());
            }
        }
        if (config == null) {
            config = new ReconConfig();
        }
        normalizeReconConfig(config, attackContext, session.getExternalIntelligence());
        try {
            session.setReconConfig(objectMapper.writeValueAsString(config));
        } catch (Exception ignored) {
            // best effort only
        }
        return config;
    }

    private void normalizeReconConfig(ReconConfig config, String attackContext,
                                      String externalIntelligence) {
        if (config.getEnabled() == null) config.setEnabled(true);
        String mode = config.getMode() != null ? config.getMode().trim().toUpperCase(Locale.ROOT) : "AUTO";
        if (mode.isBlank()) mode = "AUTO";

        boolean shortSession = containsAny((attackContext + "\n" + externalIntelligence).toLowerCase(Locale.ROOT),
                "max 3 turns", "3 turns", "three turns", "goodbye", "短会话", "最多 3 轮", "最多3轮");
        boolean richExternalIntel = externalIntelligence != null && externalIntelligence.length() >= 800;

        if ("AUTO".equals(mode)) {
            if (shortSession && richExternalIntel) {
                mode = "SKIP_TARGET_PROBE";
            } else if (shortSession) {
                mode = "LIGHT";
            } else {
                mode = "STANDARD";
            }
        }
        config.setMode(mode);

        switch (mode) {
            case "SKIP_TARGET_PROBE" -> {
                defaultIfNull(config::getMaxChats, config::setMaxChats, 0);
                defaultIfNull(config::getTurnsPerChat, config::setTurnsPerChat, 0);
                defaultIfNull(config::getMaxTotalTurns, config::setMaxTotalTurns, 0);
            }
            case "LIGHT" -> {
                defaultIfNull(config::getMaxChats, config::setMaxChats, 1);
                defaultIfNull(config::getTurnsPerChat, config::setTurnsPerChat, 1);
                defaultIfNull(config::getMaxTotalTurns, config::setMaxTotalTurns, 1);
            }
            case "DEEP" -> {
                defaultIfNull(config::getMaxChats, config::setMaxChats, 5);
                defaultIfNull(config::getTurnsPerChat, config::setTurnsPerChat, 3);
                defaultIfNull(config::getMaxTotalTurns, config::setMaxTotalTurns, 10);
            }
            default -> {
                defaultIfNull(config::getMaxChats, config::setMaxChats, 3);
                defaultIfNull(config::getTurnsPerChat, config::setTurnsPerChat, 2);
                defaultIfNull(config::getMaxTotalTurns, config::setMaxTotalTurns, 5);
            }
        }
        if (config.getCleanBeforeAttack() == null) config.setCleanBeforeAttack(true);
        config.setMaxChats(Math.max(0, config.getMaxChats()));
        config.setTurnsPerChat(Math.max(0, config.getTurnsPerChat()));
        config.setMaxTotalTurns(Math.max(0, config.getMaxTotalTurns()));
    }

    private void defaultIfNull(java.util.function.Supplier<Integer> getter,
                               java.util.function.Consumer<Integer> setter,
                               int defaultValue) {
        if (getter.get() == null) setter.accept(defaultValue);
    }

    private boolean containsAny(String source, String... needles) {
        if (source == null) return false;
        for (String needle : needles) {
            if (source.contains(needle)) return true;
        }
        return false;
    }

    private String preview(String text, int maxLen) {
        if (text == null) return "";
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > maxLen ? oneLine.substring(0, maxLen) + "..." : oneLine;
    }

    private void recordStrategySnapshot(AgentSession session,
                                        String strategyPlan,
                                        String previousStrategyPlan,
                                        String triggerType,
                                        String phase,
                                        Integer retryIndex,
                                        Integer turnIndex,
                                        Integer targetChatIndex,
                                        String replanReason,
                                        String recentPayloads) {
        if (session == null || strategyPlan == null || strategyPlan.isBlank()) {
            return;
        }
        try {
            AgentStrategySnapshot snapshot = new AgentStrategySnapshot();
            snapshot.setTaskId(session.getTaskId());
            snapshot.setAgentSessionId(session.getId());
            snapshot.setSessionIndex(session.getSessionIndex());
            snapshot.setStrategyVersion(extractStrategyVersion(strategyPlan));
            snapshot.setTriggerType(triggerType != null ? triggerType : "UNKNOWN");
            snapshot.setPhase(phase);
            snapshot.setRetryIndex(retryIndex);
            snapshot.setTurnIndex(turnIndex);
            snapshot.setTargetChatIndex(targetChatIndex);
            snapshot.setReplanReason(preview(replanReason, 1200));
            snapshot.setPreviousPlanHash(sha256(previousStrategyPlan));
            snapshot.setCurrentPlanHash(sha256(strategyPlan));
            snapshot.setRecentPayloadsPreview(preview(recentPayloads, 2000));

            StrategyValidation validation = validateStrategyPlan(strategyPlan, previousStrategyPlan, triggerType);
            snapshot.setValidationStatus(validation.status());
            snapshot.setValidationErrors(validation.errors());
            snapshot.setStrategyPlan(strategyPlan);

            strategySnapshotRepository.save(snapshot);
            log.info("AgentSession {} strategy snapshot saved: trigger={}, version={}, validation={}",
                    session.getId(), snapshot.getTriggerType(), snapshot.getStrategyVersion(),
                    snapshot.getValidationStatus());
        } catch (Exception e) {
            log.warn("AgentSession {} strategy snapshot save failed: {}",
                    session.getId(), e.getMessage());
        }
    }

    private Integer extractStrategyVersion(String strategyPlan) {
        try {
            JsonNode root = objectMapper.readTree(strategyPlan);
            if (root.has("strategyVersion")) {
                return root.path("strategyVersion").asInt();
            }
        } catch (Exception ignored) {
            // Best effort only.
        }
        return null;
    }

    private StrategyValidation validateStrategyPlan(String strategyPlan,
                                                    String previousStrategyPlan,
                                                    String triggerType) {
        List<String> errors = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(strategyPlan);
            String currentArchetypeId = root.path("strategyArchetypeId").asText("");
            if (currentArchetypeId == null || currentArchetypeId.isBlank()) {
                errors.add("strategyArchetypeId is missing; strategy library selection will be hard to audit");
            }

            String complexityLevel = root.path("complexityLevel").asText("");
            if (complexityLevel == null || complexityLevel.isBlank()) {
                errors.add("complexityLevel is missing; simple-first behavior will be hard to audit");
            } else {
                String normalizedComplexity = complexityLevel.trim().toUpperCase(Locale.ROOT);
                boolean complexRoute = "L2_CONTEXT_SETUP".equals(normalizedComplexity)
                        || "L3_MULTI_TURN".equals(normalizedComplexity);
                String whyNotSimpleRoute = root.path("whyNotSimpleRoute").asText("");
                if (complexRoute && whyNotSimpleRoute.isBlank()) {
                    errors.add("complex strategy requires whyNotSimpleRoute justification: " + complexityLevel);
                } else if (complexRoute && weakSimpleRouteRejection(whyNotSimpleRoute, root.path("negativeEvidenceAssessment"))) {
                    errors.add("complex strategy rejects simple route without observedFailure/hardBoundary evidence: "
                            + preview(whyNotSimpleRoute, 160));
                }
            }

            String currentFamily = normalizeStrategyFamily(root.path("strategyFamily").asText(""));
            if (currentFamily.isBlank()) {
                errors.add("strategyFamily is missing; route-level reskin detection will be weak");
            }

            String routeFamily = normalizeStrategyFamily(root.path("routeHypothesis").path("family").asText(""));
            if (!currentFamily.isBlank() && !routeFamily.isBlank() && !currentFamily.equals(routeFamily)) {
                errors.add("routeHypothesis.family must match strategyFamily: strategyFamily='"
                        + currentFamily + "', routeFamily='" + routeFamily + "'");
            }

            List<String> failedFamilies = new ArrayList<>();
            collectTextValues(root.path("failedFamiliesToAvoid"), failedFamilies);
            for (String failedFamily : failedFamilies) {
                if (!currentFamily.isBlank()
                        && currentFamily.equals(normalizeStrategyFamily(failedFamily))) {
                    errors.add("strategyFamily appears in failedFamiliesToAvoid: " + currentFamily);
                    break;
                }
            }

            if (isReplanTrigger(triggerType)) {
                String previousArchetypeId = extractStrategyArchetypeId(previousStrategyPlan);
                if (currentArchetypeId != null && !currentArchetypeId.isBlank()
                        && previousArchetypeId != null && !previousArchetypeId.isBlank()
                        && currentArchetypeId.equals(previousArchetypeId)) {
                    JsonNode archetypeSwitch = root.path("archetypeSwitch");
                    boolean explicitNoChange = !archetypeSwitch.path("isArchetypeChange").asBoolean(false);
                    String reason = archetypeSwitch.path("whyArchetypeChange").asText("");
                    if (explicitNoChange || reason.isBlank()) {
                        errors.add("replan kept the same strategyArchetypeId without clear archetypeSwitch justification: "
                                + currentArchetypeId);
                    }
                }

                String previousFamily = extractStrategyFamily(previousStrategyPlan);
                if (!currentFamily.isBlank() && !previousFamily.isBlank()
                        && currentFamily.equals(previousFamily)) {
                    JsonNode familySwitch = root.path("familySwitch");
                    boolean explicitNoChange = !familySwitch.path("isFamilyChange").asBoolean(false);
                    String reason = familySwitch.path("whyFamilyChange").asText("");
                    if (explicitNoChange || reason.isBlank()) {
                        errors.add("replan kept the same strategyFamily without clear familySwitch justification: "
                                + currentFamily);
                    }
                }
            }

            List<String> forbidden = new ArrayList<>();
            collectTextValues(root.path("mustNotRepeat"), forbidden);
            collectTextValues(root.path("forbiddenWords"), forbidden);

            List<String> actions = new ArrayList<>();
            collectActionTexts(root, actions);

            for (String action : actions) {
                String normalizedAction = normalizeForConflict(action);
                if (normalizedAction.isBlank()) continue;
                for (String term : forbidden) {
                    String normalizedTerm = normalizeForConflict(term);
                    if (normalizedTerm.length() < 3) continue;
                    if (normalizedAction.contains(normalizedTerm)) {
                        errors.add("concreteAction conflicts with forbidden/mustNotRepeat: action='"
                                + preview(action, 160) + "', term='" + preview(term, 80) + "'");
                    }
                }
            }
        } catch (Exception e) {
            errors.add("strategy validation parse failed: " + e.getMessage());
        }

        if (errors.isEmpty()) {
            return new StrategyValidation("PASS", "[]");
        }
        try {
            return new StrategyValidation("WARN", objectMapper.writeValueAsString(errors));
        } catch (Exception e) {
            return new StrategyValidation("WARN", errors.toString());
        }
    }

    private String extractStrategyArchetypeId(String strategyPlan) {
        if (strategyPlan == null || strategyPlan.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(strategyPlan);
            return root.path("strategyArchetypeId").asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean weakSimpleRouteRejection(String whyNotSimpleRoute, JsonNode negativeEvidenceAssessment) {
        String text = (whyNotSimpleRoute != null ? whyNotSimpleRoute : "").toLowerCase(Locale.ROOT);
        String evidenceText = negativeEvidenceAssessment != null && !negativeEvidenceAssessment.isMissingNode()
                ? negativeEvidenceAssessment.toString().toLowerCase(Locale.ROOT)
                : "";
        boolean hasHardEvidence = text.contains("observedfailure")
                || text.contains("observed failure")
                || text.contains("hardboundary")
                || text.contains("hard boundary")
                || text.contains("实测失败")
                || text.contains("硬边界")
                || evidenceText.contains("observedfailure")
                || evidenceText.contains("hardboundary")
                || evidenceText.contains("实测失败")
                || evidenceText.contains("硬边界");
        boolean hasWeakReason = text.contains("statedrefusal")
                || text.contains("stated refusal")
                || text.contains("模型说")
                || text.contains("表示不能")
                || text.contains("口头拒绝")
                || text.contains("可能")
                || text.contains("缺少上下文")
                || text.contains("为了稳妥")
                || text.contains("更稳妥");
        return hasWeakReason && !hasHardEvidence;
    }

    private boolean isReplanTrigger(String triggerType) {
        if (triggerType == null) return false;
        String normalized = triggerType.toUpperCase(Locale.ROOT);
        return normalized.contains("REPLAN");
    }

    private String extractStrategyFamily(String strategyPlan) {
        if (strategyPlan == null || strategyPlan.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(strategyPlan);
            String family = root.path("strategyFamily").asText("");
            if (family == null || family.isBlank()) {
                family = root.path("routeHypothesis").path("family").asText("");
            }
            return normalizeStrategyFamily(family);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String normalizeStrategyFamily(String family) {
        if (family == null) return "";
        return family.trim().toLowerCase(Locale.ROOT);
    }

    private void collectActionTexts(JsonNode node, List<String> output) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if ("concreteActions".equals(key) || "nextPayloadRequirements".equals(key)
                        || "attackRecommendation".equals(key)
                        || "payloadMustSatisfy".equals(key)
                        || "acceptedPatterns".equals(key)) {
                    collectTextValues(value, output);
                } else {
                    collectActionTexts(value, output);
                }
            });
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectActionTexts(item, output);
            }
        }
    }

    private void collectTextValues(JsonNode node, List<String> output) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isTextual()) {
            String text = node.asText();
            if (text != null && !text.isBlank()) {
                output.add(text);
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectTextValues(item, output);
            }
        } else if (node.isObject()) {
            node.elements().forEachRemaining(value -> collectTextValues(value, output));
        }
    }

    private String normalizeForConflict(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\u4e00-\\u9fa5]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String sha256(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : encoded) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private record StrategyValidation(String status, String errors) {}

    private void persistConversationHistory(AgentSession session,
                                            List<Map<String, Object>> completedAttempts,
                                            List<Map<String, Object>> currentAttempt) {
        try {
            List<Map<String, Object>> snapshot = new ArrayList<>(
                    completedAttempts.size() + currentAttempt.size());
            snapshot.addAll(completedAttempts);
            snapshot.addAll(currentAttempt);
            session.setConversationHistory(objectMapper.writeValueAsString(snapshot));
        } catch (Exception ex) {
            log.warn("Failed to persist in-progress conversation history for session {}",
                    session.getId(), ex);
        }
    }

    private void accumulateObservations(AgentSession session, String phase, int turnNum, String observations) {
        if (observations == null || observations.isBlank()) return;
        String entry = String.format("[%s T%d] %s", phase, turnNum, observations);
        String current = session.getIntelligenceLog();
        session.setIntelligenceLog((current != null && !current.isBlank())
                ? current + "\n" + entry
                : entry);
    }

    private String fallbackInitialMemory(String attackContext, String externalIntelligence, String intelligenceLog) {
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("brief", "Fallback memory generated without LLM preprocessing.");
        memory.put("attackContext", attackContext != null ? attackContext : "");
        memory.put("externalIntelligence", externalIntelligence != null ? externalIntelligence : "");
        memory.put("existingIntelligenceLog", intelligenceLog != null ? intelligenceLog : "");
        try {
            return objectMapper.writeValueAsString(memory);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String sanitizeBuildMemory(String memory) {
        if (memory == null || memory.isBlank()) {
            return memory;
        }
        try {
            JsonNode parsed = objectMapper.readTree(memory);
            if (!(parsed instanceof ObjectNode root)) {
                return memory;
            }

            ArrayNode doNotRepeat = ensureArray(root, "doNotRepeatVerbatim");
            boolean changed = false;
            changed |= sanitizePositiveArrayField(root, "nextPayloadRequirements", doNotRepeat,
                    "Describe abstract generation requirements only; do not prescribe exact target text.");
            changed |= sanitizePositiveArrayField(root, "evidenceToSeek", doNotRepeat,
                    "Seek target-origin evidence types, not concrete candidate strings or sample word chains.");
            changed |= sanitizePositiveArrayField(root, "acceptedPatterns", doNotRepeat,
                    "Keep only abstract successful pattern categories; do not preserve exact payload text.");

            JsonNode recommendation = root.path("attackRecommendation");
            if (recommendation.isTextual() && looksLikeConcretePayload(recommendation.asText())) {
                doNotRepeat.add("Removed concrete attackRecommendation: "
                        + preview(recommendation.asText(), 240));
                root.put("attackRecommendation",
                        "Use the validated direction only as an intent summary; generate a fresh payload under current constraints.");
                changed = true;
            }

            return changed ? objectMapper.writeValueAsString(root) : memory;
        } catch (Exception e) {
            log.warn("BuildMemory sanitize skipped: {}", e.getMessage());
            return memory;
        }
    }

    private boolean sanitizePositiveArrayField(ObjectNode root, String fieldName,
                                               ArrayNode doNotRepeat, String fallbackRequirement) {
        JsonNode field = root.path(fieldName);
        if (!field.isArray()) {
            return false;
        }
        ArrayNode sanitized = objectMapper.createArrayNode();
        boolean changed = false;
        for (JsonNode item : field) {
            String text = item.isTextual() ? item.asText() : item.toString();
            if (looksLikeConcretePayload(text)) {
                doNotRepeat.add("Removed concrete " + fieldName + ": " + preview(text, 240));
                changed = true;
            } else {
                sanitized.add(text);
            }
        }
        if (changed && sanitized.isEmpty()) {
            sanitized.add(fallbackRequirement);
        }
        if (changed) {
            root.set(fieldName, sanitized);
        }
        return changed;
    }

    private ArrayNode ensureArray(ObjectNode root, String fieldName) {
        JsonNode existing = root.path(fieldName);
        if (existing.isArray()) {
            return (ArrayNode) existing;
        }
        ArrayNode created = objectMapper.createArrayNode();
        root.set(fieldName, created);
        return created;
    }

    private boolean looksLikeConcretePayload(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("must be exactly")
                || lower.contains("send ")
                || lower.contains("exact phrase")
                || lower.contains("verbatim")
                || lower.contains("use the exact")) {
            return true;
        }
        if (text.contains("'") || text.contains("\"")) {
            String compact = text.replaceAll("\\s+", " ").trim();
            return compact.length() <= 260;
        }
        String[] words = text.trim().split("\\s+");
        return words.length >= 2 && words.length <= 8
                && !lower.contains("avoid")
                && !lower.contains("abstract")
                && !lower.contains("evidence")
                && !lower.contains("requirement")
                && !lower.contains("direction");
    }

    private String summarizeReconAndPlanSafe(AgentSession session, Task task, boolean isBrowser,
                                             String attackContext, String goal,
                                             List<String> availableTechniques, int maxTurns,
                                             List<Map<String, Object>> turnRecords,
                                             String currentTargetMemory) {
        String updatedMemory = currentTargetMemory;
        try {
            String stageTurns = objectMapper.writeValueAsString(filterTurnsByPhase(turnRecords, "RECON"));
            updatedMemory = plannerService.summarizeReconMemory(
                    attackContext, goal, currentTargetMemory, stageTurns);
            session.setTargetIntelligenceMemory(updatedMemory);
            log.info("AgentSession {} recon memory summarized ({} chars)",
                    session.getId(), updatedMemory.length());
        } catch (Exception e) {
            log.warn("AgentSession {} recon memory summary failed: {}",
                    session.getId(), e.getMessage());
        }

        try {
            String previousStrategyPlan = session.getStrategyPlan();
            String recentPayloads = buildRecentPayloads(turnRecords, 5);
            String replanReason = "RECON phase completed in fallback loop; generate BUILD/ATTACK strategy";
            AgentWorkflowContext context = AgentWorkflowContext.builder()
                    .task(task)
                    .session(session)
                    .availableTechniques(availableTechniques)
                    .browserMode(isBrowser)
                    .build();
            WorkflowNodeResult<String> planResult = strategyPlanNode.execute(context,
                    workflowState(session, task, "BUILD", session.getCurrentRetry(),
                            session.getCurrentTurn(), session.getTargetChatIndex(),
                            attackContext, updatedMemory, session.getBuildMemory(),
                            session.getAttackSignals(), previousStrategyPlan),
                    "POST_FALLBACK_RECON", availableTechniques,
                    AgentTurnBudget.beforeFirstTurn(maxTurns).remainingTurnsIncludingCurrent(),
                    session.getIntelligenceLog(), previousStrategyPlan,
                    recentPayloads, replanReason);
            String plan = planResult.getOutput();
            session.setStrategyPlan(plan);
            recordStrategySnapshot(session, plan, previousStrategyPlan,
                    "POST_FALLBACK_RECON", "BUILD", session.getCurrentRetry(),
                    session.getCurrentTurn(), session.getTargetChatIndex(),
                    replanReason, recentPayloads);
            log.info("AgentSession {} post-RECON strategy generated ({} chars)",
                    session.getId(), plan.length());
        } catch (Exception e) {
            log.warn("AgentSession {} post-RECON strategy generation failed: {}",
                    session.getId(), e.getMessage());
        }
        return updatedMemory;
    }

    private String summarizeBuildSafe(AgentSession session, String attackContext, String goal,
                                      String targetMemory, String strategyPlan,
                                      List<Map<String, Object>> turnRecords) {
        String existing = session.getBuildMemory();
        try {
            String stageTurns = objectMapper.writeValueAsString(filterTurnsByPhase(turnRecords, "BUILD"));
            String memory = plannerService.summarizeBuildMemory(
                    attackContext, goal, targetMemory, strategyPlan, stageTurns);
            memory = sanitizeBuildMemory(memory);
            session.setBuildMemory(memory);
            log.info("AgentSession {} build memory summarized ({} chars)",
                    session.getId(), memory.length());
            return memory;
        } catch (Exception e) {
            log.warn("AgentSession {} build memory summary failed: {}",
                    session.getId(), e.getMessage());
            return existing;
        }
    }

    private AgentWorkflowState workflowState(AgentSession session, Task task, String currentPhase,
                                             int currentRetry, int turnNum, int targetChatIndex,
                                             String attackContext, String targetMemory,
                                             String buildMemory, String attackSignals,
                                             String strategyPlan) {
        int maxTurns = session != null && session.getMaxTurns() != null ? session.getMaxTurns() : 0;
        AgentTurnBudget turnBudget = turnNum > 0
                ? AgentTurnBudget.forExecutingTurn(turnNum, maxTurns)
                : AgentTurnBudget.beforeFirstTurn(maxTurns);
        return AgentWorkflowState.builder()
                .taskId(task != null ? task.getId() : null)
                .agentSessionId(session != null ? session.getId() : null)
                .sessionIndex(session != null ? session.getSessionIndex() : null)
                .goal(session != null ? session.getGoal() : null)
                .phase(currentPhase)
                .retryIndex(currentRetry)
                .turnIndex(turnNum)
                .targetChatIndex(targetChatIndex)
                .maxTurns(maxTurns)
                .remainingTurnsIncludingCurrent(turnBudget.remainingTurnsIncludingCurrent())
                .remainingTurnsAfterCurrent(turnBudget.remainingTurnsAfterCurrent())
                .retryCount(session != null ? session.getRetryCount() : null)
                .route(WorkflowRoute.CONTINUE)
                .attackContext(attackContext)
                .targetIntelligenceMemory(targetMemory)
                .buildMemory(buildMemory)
                .attackSignals(attackSignals)
                .strategyPlan(strategyPlan)
                .build();
    }

    private int newChatBeforeBuildSafe(AgentSession session, Long browserSid, int targetChatIndex) {
        try {
            if (browserService.newChat(browserSid)) {
                int next = targetChatIndex + 1;
                session.setTargetChatIndex(next);
                accumulateObservations(session, "SYSTEM", session.getCurrentTurn(),
                        "RECON结束后重置目标会话，BUILD将在干净上下文中开始");
                return next;
            }
        } catch (Exception e) {
            log.warn("AgentSession {} newChat before BUILD failed: {}",
                    session.getId(), e.getMessage());
        }
        return targetChatIndex;
    }

    private List<Map<String, Object>> filterTurnsByPhase(List<Map<String, Object>> turnRecords, String phase) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> turn : turnRecords) {
            if (phase.equals(turn.get("phase"))) {
                filtered.add(turn);
            }
        }
        return filtered;
    }

    private String appendMemorySection(String existing, String title, String content) {
        String base = existing != null ? existing : "";
        String section = "\n\n## " + title + "\n" + content;
        String merged = base + section;
        int maxLen = 12000;
        if (merged.length() > maxLen) {
            return merged.substring(merged.length() - maxLen);
        }
        return merged;
    }

    private String buildRecentPayloads(List<Map<String, Object>> turnRecords, int limit) {
        if (turnRecords == null || turnRecords.isEmpty()) {
            return "（暂无最近payload）";
        }
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, turnRecords.size() - Math.max(1, limit));
        for (int i = start; i < turnRecords.size(); i++) {
            Map<String, Object> turn = turnRecords.get(i);
            Object message = turn.get("message");
            if (message == null || message.toString().isBlank()) {
                continue;
            }
            sb.append("T").append(turn.getOrDefault("turn", i + 1))
                    .append(" [").append(turn.getOrDefault("phase", "?"))
                    .append(" / ").append(turn.getOrDefault("technique", "?"))
                    .append("]: ")
                    .append(preview(message.toString(), 220))
                    .append("\n");
        }
        return sb.length() > 0 ? sb.toString() : "（最近记录中没有可发送payload）";
    }

    private Map<String, Object> traceMap(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (keyValues == null) {
            return map;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String nextPhase(String currentPhase) {
        for (int i = 0; i < PHASES.length - 1; i++) {
            if (PHASES[i].equals(currentPhase)) return PHASES[i + 1];
        }
        return currentPhase; // already at last phase
    }

    /**
     * Extract phase maxTurns from strategy plan JSON. Falls back to 3 if unparseable.
     */
    private int getPhaseMaxTurns(String strategyPlan, String currentPhase) {
        if (strategyPlan == null || strategyPlan.isBlank()) return defaultPhaseMaxTurns(currentPhase);
        try {
            JsonNode phases = objectMapper.readTree(strategyPlan).path("phases");
            if (phases.isArray()) {
                for (JsonNode p : phases) {
                    if (currentPhase.equalsIgnoreCase(p.path("phase").asText(""))) {
                        return p.path("maxTurns").asInt(3);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse phaseMaxTurns, using default 3: {}", e.getMessage());
        }
        return defaultPhaseMaxTurns(currentPhase);
    }

    private int defaultPhaseMaxTurns(String currentPhase) {
        if ("RECON".equals(currentPhase)) return 6;
        if ("BUILD".equals(currentPhase)) return 3;
        return 3;
    }

    private List<String> parseTechniques(String availableTechniquesJson) {
        try {
            return objectMapper.readValue(availableTechniquesJson,
                    new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse availableTechniques, using empty list", e);
            return Collections.emptyList();
        }
    }

    /**
     * Extract a short preview of the AI response from a turn record.
     * Takes first 200 chars after trimming, used for strategyCheck comparison.
     */
    private String getExtractedText(Map<String, Object> turnRecord) {
        Object extracted = turnRecord.get("extractedText");
        if (extracted == null) return "";
        String text = extracted.toString().trim();
        return text.isEmpty() ? "" : text.substring(0, Math.min(200, text.length()));
    }

    /**
     * Build a compact weapon usage summary from recent turn records.
     */
    private String buildWeaponHistory(List<Map<String, Object>> turnRecords) {
        if (turnRecords.isEmpty()) return "（无武器历史）";
        StringBuilder sb = new StringBuilder();
        int count = Math.min(6, turnRecords.size());
        for (int i = turnRecords.size() - count; i < turnRecords.size(); i++) {
            Map<String, Object> t = turnRecords.get(i);
            sb.append("T").append(t.get("turn"))
                    .append(":").append(t.get("technique"));
            Object verdict = t.get("verdict");
            if (verdict != null) {
                sb.append("(").append(verdict).append(")");
            } else {
                sb.append("(-)");
            }
            sb.append(" ");
        }
        return sb.toString().trim();
    }
}
