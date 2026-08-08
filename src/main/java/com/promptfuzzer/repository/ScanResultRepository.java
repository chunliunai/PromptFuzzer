package com.promptfuzzer.repository;

import com.promptfuzzer.entity.ScanResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScanResultRepository extends JpaRepository<ScanResult, Long> {

    Page<ScanResult> findByTaskId(Long taskId, Pageable pageable);

    List<ScanResult> findAllByTaskId(Long taskId);

    long countByTaskIdAndVerdict(Long taskId, ScanResult.Verdict verdict);

    void deleteByTaskId(Long taskId);
}
