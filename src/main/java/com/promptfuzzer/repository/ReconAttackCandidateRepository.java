package com.promptfuzzer.repository;

import com.promptfuzzer.entity.ReconAttackCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReconAttackCandidateRepository
        extends JpaRepository<ReconAttackCandidate, Long> {

    List<ReconAttackCandidate> findByReconResultIdOrderByIdAsc(Long reconResultId);

    List<ReconAttackCandidate> findByTaskId(Long taskId);

    void deleteByReconResultId(Long reconResultId);

    void deleteByAttackUnitId(Long attackUnitId);
}
