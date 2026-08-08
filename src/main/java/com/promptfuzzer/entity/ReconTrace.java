package com.promptfuzzer.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "recon_trace")
public class ReconTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reconTaskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(64)")
    private ActionType actionType;

    private Integer targetChatIndex;
    private Integer turnIndex;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String requestMessage;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String rawResponse;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String extractedText;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String observation;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(64)")
    private EvidenceType evidenceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.SUCCESS;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private Long durationMs;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum ActionType {
        READ_TARGET,
        OPEN_CAPABILITY_DISCOVERY,
        COVERAGE_PLAN,
        CAPABILITY_DISCOVERY,
        COVERAGE_GAP_ANALYSIS,
        GAP_FOLLOWUP,
        CAPABILITY_VERIFY,
        EVIDENCE_SUMMARY,
        APPLICATION_SURFACE_SYNTHESIS,
        SURFACE_QUALITY_GATE,
        RESULT_SUMMARY
    }

    public enum EvidenceType {
        AGENT_CLAIM,
        PARTIALLY_OBSERVED,
        TOOL_OBSERVED,
        RESULT_OBSERVED,
        OBSERVED,
        EXTERNAL_EVIDENCE
    }

    public enum Status {
        SUCCESS, ERROR, SKIPPED
    }
}
