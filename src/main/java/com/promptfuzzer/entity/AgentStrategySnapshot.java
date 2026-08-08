package com.promptfuzzer.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "agent_strategy_snapshot")
public class AgentStrategySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private Long agentSessionId;

    private Integer sessionIndex;

    private Integer strategyVersion;

    @Column(nullable = false)
    private String triggerType;

    private String phase;

    private Integer retryIndex;

    private Integer turnIndex;

    private Integer targetChatIndex;

    @Column(columnDefinition = "TEXT")
    private String replanReason;

    private String previousPlanHash;

    private String currentPlanHash;

    @Column(columnDefinition = "TEXT")
    private String recentPayloadsPreview;

    @Column(columnDefinition = "TEXT")
    private String validationStatus;

    @Column(columnDefinition = "TEXT")
    private String validationErrors;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String strategyPlan;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
