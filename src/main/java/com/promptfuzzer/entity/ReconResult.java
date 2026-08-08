package com.promptfuzzer.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "recon_result")
public class ReconResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long reconTaskId;

    @Column(columnDefinition = "TEXT")
    private String targetFingerprint;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String businessProfile;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String responseProfile;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String capabilityInventory;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String openDiscovery;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String coveragePlan;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String coverageMatrix;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String capabilityFacts;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String toolInventory;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String unresolvedCapabilities;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String supportingEvidence;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String unresolvedQuestions;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String surfaceQualityIssues;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String targetIntelligenceMemory;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String rawResult;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String currentResult;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QualityStatus qualityStatus = QualityStatus.PARTIAL;

    @Column(nullable = false)
    private Integer version = 1;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum QualityStatus {
        SUFFICIENT, PARTIAL, INSUFFICIENT
    }
}
