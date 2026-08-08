package com.promptfuzzer.repository;

import com.promptfuzzer.entity.ReconApplicationSurface;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReconApplicationSurfaceRepository
        extends JpaRepository<ReconApplicationSurface, Long> {

    List<ReconApplicationSurface> findByReconResultIdAndDeletedFalseOrderByIdAsc(Long reconResultId);

    List<ReconApplicationSurface> findByParentSurfaceIdAndDeletedFalseOrderByIdAsc(
            Long parentSurfaceId);

    void deleteByReconResultId(Long reconResultId);
}
