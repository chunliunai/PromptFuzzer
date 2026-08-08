package com.promptfuzzer.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "recon_attack_unit")
public class ReconAttackUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reconResultId;

    @Column(length = 64)
    private String generationId;

    @Column(nullable = false, length = 160)
    private String unitKey;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String primarySurfaceIds;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String supportingSurfaceIds = "[]";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String negativeBoundarySurfaceIds = "[]";

    @Column(columnDefinition = "TEXT")
    private String resourceBoundary;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String securityControls = "[]";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String riskDimensions = "[]";

    @Column(columnDefinition = "TEXT")
    private String aggregationReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(32)")
    private RiskLevel riskLevel = RiskLevel.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(32)")
    private ConfidenceLevel confidence = ConfidenceLevel.LOW;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String sourceSurfaceSnapshot;

    @Column(nullable = false)
    private Boolean createdByAi = true;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum RiskLevel {
        HIGH, MEDIUM, LOW
    }

    public enum ConfidenceLevel {
        HIGH, MEDIUM, LOW
    }
}
