package com.promptfuzzer.repository;

import com.promptfuzzer.entity.OobTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OobTargetRepository extends JpaRepository<OobTarget, Long> {

    Optional<OobTarget> findFirstByTaskIdOrderByIdAsc(Long taskId);

    void deleteByTaskId(Long taskId);
}
