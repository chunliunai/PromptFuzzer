package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.ReconApplicationSurface;
import com.promptfuzzer.entity.ReconResult;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.repository.ReconApplicationSurfaceRepository;
import com.promptfuzzer.repository.ReconResultRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconIntelligenceServiceTest {

    private final ReconResultRepository resultRepository =
            mock(ReconResultRepository.class);
    private final ReconApplicationSurfaceRepository surfaceRepository =
            mock(ReconApplicationSurfaceRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReconIntelligenceService service = new ReconIntelligenceService(
            resultRepository, surfaceRepository, objectMapper);

    @Test
    void loadsCurrentResultAndCurrentActiveApplicationSurfaces() throws Exception {
        ReconResult result = new ReconResult();
        result.setId(12L);
        result.setVersion(3);
        result.setQualityStatus(ReconResult.QualityStatus.SUFFICIENT);
        result.setCurrentResult("{\"businessProfile\":\"edited profile\"}");
        result.setTargetIntelligenceMemory("{\"brief\":\"recon memory\"}");
        when(resultRepository.findById(12L)).thenReturn(Optional.of(result));

        ReconApplicationSurface surface = new ReconApplicationSurface();
        surface.setId(21L);
        surface.setReconResultId(12L);
        surface.setOriginalTitle("Original title");
        surface.setCurrentTitle("User-edited title");
        surface.setCurrentDescription("Current description");
        surface.setRelatedTool("get_metrics");
        surface.setEvidenceSource(ReconApplicationSurface.EvidenceSource.OBSERVED);
        surface.setVerificationStatus(
                ReconApplicationSurface.VerificationStatus.VERIFIED);
        surface.setSourceType(ReconApplicationSurface.SourceType.AI_GENERATED);
        surface.setUserEdited(true);
        when(surfaceRepository.findByReconResultIdAndDeletedFalseOrderByIdAsc(12L))
                .thenReturn(List.of(surface));

        JsonNode memory = objectMapper.readTree(
                service.loadTargetIntelligenceMemory(12L));

        assertEquals("RECON_RESULT", memory.path("source").asText());
        assertEquals("edited profile",
                memory.path("currentResult").path("businessProfile").asText());
        assertEquals("User-edited title",
                memory.path("applicationSurfaces").get(0).path("title").asText());
        assertEquals("VERIFIED",
                memory.path("applicationSurfaces").get(0)
                        .path("verificationStatus").asText());
        verify(surfaceRepository)
                .findByReconResultIdAndDeletedFalseOrderByIdAsc(12L);
    }

    @Test
    void loadsOnlySelectedApplicationSurfacesWhenProvided() throws Exception {
        ReconResult result = new ReconResult();
        result.setId(12L);
        result.setVersion(1);
        when(resultRepository.findById(12L)).thenReturn(Optional.of(result));

        ReconApplicationSurface first = surface(21L, "First surface");
        ReconApplicationSurface second = surface(22L, "Second surface");
        when(surfaceRepository.findByReconResultIdAndDeletedFalseOrderByIdAsc(12L))
                .thenReturn(List.of(first, second));

        JsonNode memory = objectMapper.readTree(
                service.loadTargetIntelligenceMemory(12L, List.of(22L)));

        assertEquals("SELECTED", memory.path("surfaceSelectionMode").asText());
        assertEquals(22L, memory.path("selectedSurfaceIds").get(0).asLong());
        assertEquals(1, memory.path("applicationSurfaces").size());
        assertEquals("Second surface",
                memory.path("applicationSurfaces").get(0).path("title").asText());
    }

    @Test
    void rejectsSelectedSurfaceOutsideReconResult() {
        ReconResult result = new ReconResult();
        result.setId(12L);
        when(resultRepository.findById(12L)).thenReturn(Optional.of(result));
        when(surfaceRepository.findByReconResultIdAndDeletedFalseOrderByIdAsc(12L))
                .thenReturn(List.of(surface(21L, "First surface")));

        assertThrows(IllegalArgumentException.class,
                () -> service.loadTargetIntelligenceMemory(12L, List.of(404L)));
    }

    @Test
    void expandsSelectedDomainAndInjectsParentContext() throws Exception {
        ReconResult result = new ReconResult();
        result.setId(12L);
        result.setVersion(1);
        when(resultRepository.findById(12L)).thenReturn(Optional.of(result));

        ReconApplicationSurface domain = surface(30L, "报告产物");
        domain.setCapabilityKey("REPORT_OUTPUT");
        domain.setSurfaceLevel(ReconApplicationSurface.SurfaceLevel.DOMAIN);
        domain.setSelectable(false);
        ReconApplicationSurface child = surface(31L, "支持 PNG 图表渲染");
        child.setCapabilityKey("REPORT_OUTPUT.PNG");
        child.setParentSurfaceId(30L);
        when(surfaceRepository.findByReconResultIdAndDeletedFalseOrderByIdAsc(12L))
                .thenReturn(List.of(domain, child));

        JsonNode memory = objectMapper.readTree(
                service.loadTargetIntelligenceMemory(12L, List.of(30L)));

        assertEquals(1, memory.path("applicationSurfaces").size());
        assertEquals(31L,
                memory.path("effectiveSelectedSurfaceIds").get(0).asLong());
        assertEquals("报告产物", memory.path("applicationSurfaces").get(0)
                .path("parentTitle").asText());
    }

    @Test
    void initializesSessionAtBuildByDefault() {
        Task task = taskWithRecon(false);
        AgentSession session = new AgentSession();

        service.initializeSession(session, task, "{\"source\":\"RECON_RESULT\"}");

        assertEquals("BUILD", session.getCurrentPhase());
        assertTrue(session.getReconCompleted());
        assertEquals("{\"source\":\"RECON_RESULT\"}",
                session.getTargetIntelligenceMemory());
    }

    @Test
    void initializesSessionAtReconWhenSupplementalReconIsEnabled() {
        Task task = taskWithRecon(true);
        AgentSession session = new AgentSession();

        service.initializeSession(session, task, "memory");

        assertEquals("RECON", session.getCurrentPhase());
        assertFalse(session.getReconCompleted());
    }

    @Test
    void rejectsUnknownReconResult() {
        when(resultRepository.findById(404L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.loadTargetIntelligenceMemory(404L));
    }

    private Task taskWithRecon(boolean supplementalRecon) {
        Task task = new Task();
        task.setReconResultId(12L);
        task.setSupplementalRecon(supplementalRecon);
        return task;
    }

    private ReconApplicationSurface surface(Long id, String title) {
        ReconApplicationSurface surface = new ReconApplicationSurface();
        surface.setId(id);
        surface.setReconResultId(12L);
        surface.setCurrentTitle(title);
        surface.setCurrentDescription("Description");
        surface.setEvidenceSource(ReconApplicationSurface.EvidenceSource.OBSERVED);
        surface.setVerificationStatus(
                ReconApplicationSurface.VerificationStatus.VERIFIED);
        surface.setSourceType(ReconApplicationSurface.SourceType.AI_GENERATED);
        surface.setSurfaceLevel(
                ReconApplicationSurface.SurfaceLevel.CAPABILITY);
        surface.setSelectable(true);
        return surface;
    }
}
