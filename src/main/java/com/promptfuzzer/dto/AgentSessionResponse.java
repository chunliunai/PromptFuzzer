package com.promptfuzzer.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.AgentStrategySnapshot;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Slf4j
@Data
public class AgentSessionResponse {

    private Long id;
    private Long taskId;
    private Integer sessionIndex;
    private String goal;
    private List<String> availableTechniques;
    private Integer maxTurns;
    private Integer currentTurn;
    private Integer retryCount;
    private Integer currentRetry;
    private String status;
    private String finalVerdict;
    private String finalEvidence;
    private Integer winningTurn;
    private String winningTechnique;
    private String winningMessage;
    private String targetIntelligenceMemory;
    private String buildMemory;
    private String attackSignals;
    private String reconConfig;
    private Boolean reconCompleted;
    private Integer targetChatIndex;
    private List<StrategySnapshotRecord> strategySnapshots;
    private List<TurnRecord> turns;
    private LocalDateTime createdAt;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TurnRecord {
        private int turn;
        private String phase;
        private String action;
        private String technique;
        private String analysis;
        private String message;
        private String extractedText;
        private String verdict;
        private String evidence;
        private Integer targetChatIndex;
        private Boolean shouldStop;
        private String stopReason;
    }

    @Data
    public static class StrategySnapshotRecord {
        private Long id;
        private Integer strategyVersion;
        private String triggerType;
        private String phase;
        private Integer retryIndex;
        private Integer turnIndex;
        private Integer targetChatIndex;
        private String replanReason;
        private String previousPlanHash;
        private String currentPlanHash;
        private String recentPayloadsPreview;
        private String validationStatus;
        private String validationErrors;
        private String strategyPlan;
        private LocalDateTime createdAt;

        public static StrategySnapshotRecord from(AgentStrategySnapshot snapshot) {
            StrategySnapshotRecord r = new StrategySnapshotRecord();
            r.setId(snapshot.getId());
            r.setStrategyVersion(snapshot.getStrategyVersion());
            r.setTriggerType(snapshot.getTriggerType());
            r.setPhase(snapshot.getPhase());
            r.setRetryIndex(snapshot.getRetryIndex());
            r.setTurnIndex(snapshot.getTurnIndex());
            r.setTargetChatIndex(snapshot.getTargetChatIndex());
            r.setReplanReason(snapshot.getReplanReason());
            r.setPreviousPlanHash(snapshot.getPreviousPlanHash());
            r.setCurrentPlanHash(snapshot.getCurrentPlanHash());
            r.setRecentPayloadsPreview(snapshot.getRecentPayloadsPreview());
            r.setValidationStatus(snapshot.getValidationStatus());
            r.setValidationErrors(snapshot.getValidationErrors());
            r.setStrategyPlan(snapshot.getStrategyPlan());
            r.setCreatedAt(snapshot.getCreatedAt());
            return r;
        }
    }

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Summary view (for agent list) — no turns detail, but extracts winning info if SUCCESS.
     */
    public static AgentSessionResponse summary(AgentSession session) {
        AgentSessionResponse r = new AgentSessionResponse();
        r.setId(session.getId());
        r.setTaskId(session.getTaskId());
        r.setSessionIndex(session.getSessionIndex());
        r.setGoal(session.getGoal());
        r.setMaxTurns(session.getMaxTurns());
        r.setCurrentTurn(session.getCurrentTurn());
        r.setRetryCount(session.getRetryCount());
        r.setCurrentRetry(session.getCurrentRetry());
        r.setStatus(session.getStatus().name());
        r.setFinalVerdict(session.getFinalVerdict() != null ? session.getFinalVerdict().name() : null);
        r.setReconConfig(session.getReconConfig());
        r.setReconCompleted(session.getReconCompleted());
        r.setTargetChatIndex(session.getTargetChatIndex());
        r.setCreatedAt(session.getCreatedAt());

        // If SUCCESS, extract the winning turn info from conversation history
        if (session.getStatus() == AgentSession.SessionStatus.SUCCESS
                && session.getConversationHistory() != null) {
            List<TurnRecord> turns = parseTurns(session.getConversationHistory());
            for (TurnRecord t : turns) {
                if ("SUCCESS".equals(t.getVerdict())) {
                    r.setWinningTurn(t.getTurn());
                    r.setWinningTechnique(t.getTechnique());
                    String msg = t.getMessage();
                    r.setWinningMessage(msg != null && msg.length() > 100
                            ? msg.substring(0, 100) + "..." : msg);
                    break;
                }
            }
            // Composite review success: no individual turn has SUCCESS verdict,
            // but finalVerdict=SUCCESS means the review found it in accumulated text
            if (r.getWinningTurn() == null) {
                r.setWinningTurn(-1);
                r.setWinningTechnique("composite_review");
                r.setWinningMessage("(Composite Review found the secret word across non-ATTACK turns)");
            }
        }
        return r;
    }

    /**
     * Detail view (for single agent) — with full turn records
     */
    public static AgentSessionResponse detail(AgentSession session) {
        AgentSessionResponse r = summary(session);
        r.setFinalEvidence(session.getFinalEvidence());
        r.setTargetIntelligenceMemory(session.getTargetIntelligenceMemory());
        r.setBuildMemory(session.getBuildMemory());
        r.setAttackSignals(session.getAttackSignals());
        r.setTurns(parseTurns(session.getConversationHistory()));
        try {
            r.setAvailableTechniques(mapper.readValue(session.getAvailableTechniques(),
                    new TypeReference<List<String>>() {}));
        } catch (Exception e) {
            r.setAvailableTechniques(Collections.emptyList());
        }
        return r;
    }

    private static List<TurnRecord> parseTurns(String conversationHistory) {
        if (conversationHistory == null || conversationHistory.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return mapper.readValue(conversationHistory,
                    new TypeReference<List<TurnRecord>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse conversationHistory for agent session", e);
            return Collections.emptyList();
        }
    }
}
