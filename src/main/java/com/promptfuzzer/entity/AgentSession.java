package com.promptfuzzer.entity;

import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "agent_session")
public class AgentSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private Integer sessionIndex;

    private String goal;

    @Column(columnDefinition = "TEXT")
    private String availableTechniques;

    @Column(nullable = false)
    private Integer maxTurns = 5;

    @Column(nullable = false)
    private Integer currentTurn = 0;

    private Integer retryCount = 0;
    private Integer currentRetry = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status = SessionStatus.PENDING;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String conversationHistory;

    @Enumerated(EnumType.STRING)
    private Verdict finalVerdict;

    @Column(columnDefinition = "TEXT")
    private String finalEvidence;

    /**
     * Current phase: RECON / BUILD / ATTACK
     */
    private String currentPhase = "RECON";

    /**
     * Accumulated intelligence across turns: structured observations from Planner.
     * Each turn appends one line like: "[RECON T1] 角色询问触发层2 → AI回复'I'm here to help with...'"
     */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String intelligenceLog = "";

    /**
     * Strategy plan JSON from strategy_plan.txt, generated before the first turn.
     * May be regenerated if strategyInvalidated=true.
     */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String strategyPlan;

    /**
     * User-provided external intelligence for this agent session.
     * This is internal knowledge for the Agent, not text to send directly to the target.
     */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String externalIntelligence;

    /**
     * Per-session RECON subroutine configuration JSON.
     */
    @Column(columnDefinition = "TEXT")
    private String reconConfig;

    /**
     * Whether the independent RECON subroutine has completed for this session.
     */
    private Boolean reconCompleted = false;

    /**
     * Session-level target intelligence memory.
     * Stores user-provided intel, RECON observations, inferred vectors, refusal patterns,
     * source turn ids, and target chat indexes as structured JSON/text.
     */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String targetIntelligenceMemory;

    /**
     * BUILD-stage memory: selected vector, business pretexts, accepted/rejected patterns,
     * target tool/params, same-chat requirement, and ATTACK recommendations.
     */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String buildMemory;

    /**
     * ATTACK feedback and per-turn memory deltas.
     */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String attackSignals;

    /**
     * Target-side chat/session index. Incremented when the Agent performs NEW_CHAT.
     */
    private Integer targetChatIndex = 0;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum SessionStatus {
        PENDING, RUNNING, SUCCESS, BLOCKED, ERROR
    }

    public enum Verdict {
        SUCCESS, FAIL, UNCERTAIN
    }
}
