package com.promptfuzzer.entity;

import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "oob_target")
public class OobTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private String goal;

    @Column(nullable = false)
    private String mode = "DNSLOG";

    @Column(nullable = false)
    private String status = "READY";

    private String provider;

    @Column(columnDefinition = "TEXT")
    private String domain;

    @Column(columnDefinition = "TEXT")
    private String oobUrl;

    private String rootDomain;

    private String token;

    private String nonce;

    @Column(columnDefinition = "TEXT")
    private String failureReason;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String rawEvents;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
