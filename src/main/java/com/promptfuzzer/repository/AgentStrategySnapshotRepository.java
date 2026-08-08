package com.promptfuzzer.repository;

import com.promptfuzzer.entity.AgentStrategySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentStrategySnapshotRepository extends JpaRepository<AgentStrategySnapshot, Long> {

    List<AgentStrategySnapshot> findByAgentSessionIdOrderByIdAsc(Long agentSessionId);

    void deleteByTaskId(Long taskId);
}
