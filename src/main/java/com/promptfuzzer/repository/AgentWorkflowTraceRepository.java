package com.promptfuzzer.repository;

import com.promptfuzzer.entity.AgentWorkflowTrace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentWorkflowTraceRepository extends JpaRepository<AgentWorkflowTrace, Long> {

    List<AgentWorkflowTrace> findByAgentSessionIdOrderByIdAsc(Long agentSessionId);

    void deleteByTaskId(Long taskId);
}
