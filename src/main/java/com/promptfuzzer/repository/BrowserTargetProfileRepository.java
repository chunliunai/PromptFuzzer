package com.promptfuzzer.repository;

import com.promptfuzzer.entity.BrowserTargetProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BrowserTargetProfileRepository extends JpaRepository<BrowserTargetProfile, Long> {

    Optional<BrowserTargetProfile> findByTargetType(String targetType);
}
