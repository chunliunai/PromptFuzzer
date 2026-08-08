package com.promptfuzzer.repository;

import com.promptfuzzer.entity.ReconTrace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReconTraceRepository extends JpaRepository<ReconTrace, Long> {

    List<ReconTrace> findByReconTaskIdOrderByIdAsc(Long reconTaskId);

    void deleteByReconTaskId(Long reconTaskId);
}
