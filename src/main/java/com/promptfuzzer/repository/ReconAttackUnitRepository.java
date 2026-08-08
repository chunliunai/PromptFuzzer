package com.promptfuzzer.repository;

import com.promptfuzzer.entity.ReconAttackUnit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReconAttackUnitRepository
        extends JpaRepository<ReconAttackUnit, Long> {

    List<ReconAttackUnit> findByReconResultIdOrderByIdAsc(Long reconResultId);

    void deleteByReconResultId(Long reconResultId);
}
