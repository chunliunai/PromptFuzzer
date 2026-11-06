package com.promptfuzzer.entity;

import lombok.Data;

import jakarta.persistence.*;

@Data
@Entity
@Table(name = "scan_result")
public class ScanResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long payloadId;

    private Long taskId;

    @Column(columnDefinition = "TEXT")
    private String requestBody;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String responseBody;

    private Integer responseTime;

    @Enumerated(EnumType.STRING)
    private Verdict verdict;

    private String harmType;

    @Column(columnDefinition = "TEXT")
    private String extractedText;

    @Column(columnDefinition = "TEXT")
    private String evidence;

    @Column(columnDefinition = "TEXT")
    private String rawJudgeResponse;

    @Column(length = 32)
    private String executionChannel;

    private Long replayProfileId;

    @Column(length = 32)
    private String replayProfileClassification;

    private Integer statusCode;

    @Column(length = 160)
    private String contentType;

    private Integer targetChatIndex;

    public enum Verdict {
        SUCCESS, FAIL, UNCERTAIN
    }
}
