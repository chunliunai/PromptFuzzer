package com.promptfuzzer.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "browser_target_profile",
        uniqueConstraints = @UniqueConstraint(columnNames = "targetType"))
public class BrowserTargetProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String targetType;

    private String displayName;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String chatUrl;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String selectorsJson;

    @Column(columnDefinition = "TEXT")
    private String loginJson;

    @Column(columnDefinition = "TEXT")
    private String loginCheckJson;

    private Integer waitTimeoutMs;

    private String profilePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthState authState = AuthState.UNKNOWN;

    private LocalDateTime lastVerifiedAt;

    private LocalDateTime lastLoginAt;

    @Column(columnDefinition = "TEXT")
    private String lastFailureReason;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum AuthState {
        UNKNOWN,
        WAITING_LOGIN,
        VERIFYING,
        VERIFIED,
        EXPIRED,
        FAILED
    }
}
