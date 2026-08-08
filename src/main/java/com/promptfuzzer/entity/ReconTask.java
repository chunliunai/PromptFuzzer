package com.promptfuzzer.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "recon_task")
public class ReconTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScanMode scanMode = ScanMode.BROWSER;

    @Column(columnDefinition = "TEXT")
    private String reconContext;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String externalIntelligence;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String targetConfig;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String rawRequestTemplate;

    @Column(columnDefinition = "TEXT")
    private String reconConfig;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    public enum ScanMode {
        BROWSER, HTTP_TEMPLATE
    }

    public enum Status {
        PENDING, RUNNING, COMPLETED, FAILED
    }
}
