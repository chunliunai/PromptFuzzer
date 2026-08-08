package com.promptfuzzer.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "recon_application_surface")
public class ReconApplicationSurface {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reconResultId;

    private Long parentSurfaceId;

    @Column(length = 160)
    private String capabilityKey;

    @Enumerated(EnumType.STRING)
    private SurfaceLevel surfaceLevel = SurfaceLevel.CAPABILITY;

    private Boolean selectable = true;

    @Column(nullable = false)
    private String originalTitle;

    @Column(columnDefinition = "TEXT")
    private String originalDescription;

    @Column(nullable = false)
    private String currentTitle;

    @Column(columnDefinition = "TEXT")
    private String currentDescription;

    private String surfaceType;
    private String relatedTool;

    @Column(columnDefinition = "TEXT")
    private String supportedActions;

    @Column(columnDefinition = "TEXT")
    private String resourceScope;

    @Enumerated(EnumType.STRING)
    private EvidenceSource evidenceSource = EvidenceSource.AGENT_CLAIM;

    @Enumerated(EnumType.STRING)
    private VerificationStatus verificationStatus = VerificationStatus.UNVERIFIED;

    @Column(columnDefinition = "TEXT")
    private String sourceTraceIds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceType sourceType = SourceType.AI_GENERATED;

    @Column(nullable = false)
    private Boolean userEdited = false;

    @Column(nullable = false)
    private Boolean deleted = false;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum EvidenceSource {
        AGENT_CLAIM, OBSERVED, EXTERNAL_EVIDENCE
    }

    public enum SurfaceLevel {
        DOMAIN, CAPABILITY
    }

    public enum VerificationStatus {
        UNVERIFIED, PARTIALLY_VERIFIED, VERIFIED, CONTRADICTED
    }

    public enum SourceType {
        AI_GENERATED, USER_ADDED
    }
}
