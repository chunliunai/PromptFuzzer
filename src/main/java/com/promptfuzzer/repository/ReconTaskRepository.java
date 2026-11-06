package com.promptfuzzer.repository;

import com.promptfuzzer.entity.ReconTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;

@Repository
public interface ReconTaskRepository extends JpaRepository<ReconTask, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update ReconTask task
            set task.status = :newStatus,
                task.errorMessage = :errorMessage,
                task.completedAt = :completedAt,
                task.updatedAt = :updatedAt
            where task.id = :taskId
              and task.status in :expectedStatuses
            """)
    int transitionStatus(
            @Param("taskId") Long taskId,
            @Param("expectedStatuses") Collection<ReconTask.Status> expectedStatuses,
            @Param("newStatus") ReconTask.Status newStatus,
            @Param("errorMessage") String errorMessage,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("updatedAt") LocalDateTime updatedAt);
}
