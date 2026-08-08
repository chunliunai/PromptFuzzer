package com.promptfuzzer.repository;

import com.promptfuzzer.entity.ReconTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReconTaskRepository extends JpaRepository<ReconTask, Long> {
}
