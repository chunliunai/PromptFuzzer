package com.promptfuzzer.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "recon_attack_candidate")
public class ReconAttackCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reconResultId;

    private Long attackUnitId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String surfaceIds;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private String recommendedGoal;

    @Column(nullable = false)
    private String recommendedAttackMode = "DUAL";

    @Column(columnDefinition = "TEXT")
    private String rationale;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String attackContext;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String requestBody;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CandidateStatus status = CandidateStatus.DRAFT;

    private Long taskId;

    @Column(nullable = false)
    private Boolean createdByAi = true;

    @Column(nullable = false)
    private Boolean userEdited = false;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum CandidateStatus {
        DRAFT, EXECUTED, DISCARDED
    }
}
