package com.promptfuzzer.entity;

import lombok.Data;

import jakarta.persistence.*;

@Data
@Entity
@Table(name = "scan_payload")
public class ScanPayload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    private String strategyId;
    private String goal;
    private String technique;
    private String templateName;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PayloadStatus status = PayloadStatus.PENDING;

    public enum PayloadStatus {
        PENDING, SENT, JUDGED, ERROR
    }
}
