package com.promptfuzzer.repository;

import com.promptfuzzer.entity.ReconResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReconResultRepository extends JpaRepository<ReconResult, Long> {

    Optional<ReconResult> findByReconTaskId(Long reconTaskId);
}
