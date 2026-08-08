package com.promptfuzzer.repository;

import com.promptfuzzer.entity.ScanPayload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScanPayloadRepository extends JpaRepository<ScanPayload, Long> {

    List<ScanPayload> findByTaskId(Long taskId);

    List<ScanPayload> findByTaskIdAndStatus(Long taskId, ScanPayload.PayloadStatus status);

    long countByTaskId(Long taskId);

    void deleteByTaskId(Long taskId);
}
