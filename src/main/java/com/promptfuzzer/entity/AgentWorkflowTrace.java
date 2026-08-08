package com.promptfuzzer.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "agent_workflow_trace")
public class AgentWorkflowTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private Long agentSessionId;

    private Integer sessionIndex;

    @Column(nullable = false)
    private String nodeName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NodeStatus status = NodeStatus.SUCCESS;

    private String phase;
    private Integer retryIndex;
    private Integer turnIndex;
    private Integer targetChatIndex;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String inputSnapshot;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String outputSnapshot;

    private String verdict;

    @Column(columnDefinition = "TEXT")
    private String evidence;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private Long durationMs;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum NodeStatus {
        SUCCESS, ERROR, SKIPPED
    }
}
