package com.promptfuzzer.entity;

import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "task")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScanMode scanMode = ScanMode.HTTP_TEMPLATE;

    @Column(columnDefinition = "TEXT")
    private String rawRequestTemplate;

    private String promptFieldPath;

    @Column(columnDefinition = "TEXT", name = "attack_context")
    private String attackContext;

    @Column(columnDefinition = "MEDIUMTEXT", name = "external_intelligence")
    private String externalIntelligence;

    @Column(columnDefinition = "TEXT", name = "recon_config")
    private String reconConfig;

    @Column(name = "recon_result_id")
    private Long reconResultId;

    @Column(nullable = false, name = "supplemental_recon")
    private Boolean supplementalRecon = false;

    @Column(columnDefinition = "TEXT", name = "selected_surface_ids")
    private String selectedSurfaceIds;

    /** 浏览器模式专用：目标网站配置 JSON（scanMode=BROWSER 时使用） */
    @Column(columnDefinition = "TEXT", name = "target_config")
    private String targetConfig;

    @Column(columnDefinition = "TEXT")
    private String strategyIds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status = TaskStatus.PENDING;

    private int totalCount = 0;
    private int successCount = 0;
    private int failCount = 0;
    private int uncertainCount = 0;

    @Column(columnDefinition = "TEXT")
    private String reportSummary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20) DEFAULT 'SINGLE_TURN'")
    private AttackMode attackMode = AttackMode.SINGLE_TURN;

    private Integer maxTurns = 5;
    private Integer retryCount = 0;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum ScanMode {
        HTTP_TEMPLATE, BROWSER
    }

    public enum AttackMode {
        SINGLE_TURN, AGENT, AUTO, DUAL
    }

    public enum TaskStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }
}
