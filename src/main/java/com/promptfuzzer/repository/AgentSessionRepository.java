package com.promptfuzzer.repository;

import com.promptfuzzer.entity.AgentSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {

    List<AgentSession> findByTaskId(Long taskId);

    List<AgentSession> findByTaskIdOrderBySessionIndexAsc(Long taskId);

    AgentSession findByTaskIdAndSessionIndex(Long taskId, Integer sessionIndex);

    void deleteByTaskId(Long taskId);
}
