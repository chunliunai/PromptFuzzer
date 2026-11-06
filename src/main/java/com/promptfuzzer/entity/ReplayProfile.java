package com.promptfuzzer.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "replay_profile")
public class ReplayProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    private Long agentSessionId;
    private Integer sessionIndex;
    private Integer turnIndex;
    private Integer targetChatIndex;

    @Column(length = 160)
    private String targetType;

    @Column(nullable = false, length = 128)
    private String endpointFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Classification classification = Classification.UNKNOWN;

    @Column(nullable = false, length = 16)
    private String method;

    @Column(columnDefinition = "TEXT")
    private String url;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String requestTemplate;

    @Column(length = 256)
    private String payloadFieldPath;

    @Column(columnDefinition = "TEXT")
    private String dynamicBindings;

    @Column(columnDefinition = "TEXT")
    private String responseExtractionRule;

    private Integer validationCount = 0;
    private LocalDateTime lastValidatedAt;
    private Integer failureCount = 0;

    @Column(columnDefinition = "TEXT")
    private String lastFailureReason;

    @Column(columnDefinition = "TEXT")
    private String evidenceSummary;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum Classification {
        HTTP_REPLAYABLE, BROWSER_ONLY, UNKNOWN
    }
}
