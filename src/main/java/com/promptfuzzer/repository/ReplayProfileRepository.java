package com.promptfuzzer.repository;

import com.promptfuzzer.entity.ReplayProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReplayProfileRepository extends JpaRepository<ReplayProfile, Long> {

    List<ReplayProfile> findByTaskIdOrderByUpdatedAtDesc(Long taskId);

    List<ReplayProfile> findByTaskIdAndTargetChatIndexOrderByUpdatedAtDesc(
            Long taskId, Integer targetChatIndex);

    Optional<ReplayProfile> findFirstByTaskIdAndEndpointFingerprintOrderByUpdatedAtDesc(
            Long taskId, String endpointFingerprint);

    void deleteByTaskId(Long taskId);
}
