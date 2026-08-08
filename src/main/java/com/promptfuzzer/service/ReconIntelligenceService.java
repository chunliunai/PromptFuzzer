package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptfuzzer.entity.AgentSession;
import com.promptfuzzer.entity.ReconApplicationSurface;
import com.promptfuzzer.entity.ReconResult;
import com.promptfuzzer.entity.Task;
import com.promptfuzzer.repository.ReconApplicationSurfaceRepository;
import com.promptfuzzer.repository.ReconResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReconIntelligenceService {

    private final ReconResultRepository reconResultRepository;
    private final ReconApplicationSurfaceRepository applicationSurfaceRepository;
    private final ObjectMapper objectMapper;

    public String loadTargetIntelligenceMemory(Long reconResultId) {
        return loadTargetIntelligenceMemory(reconResultId, null);
    }

    public List<Long> resolveSelectedSurfaceIds(
            Long reconResultId,
            List<Long> selectedSurfaceIds) {
        if (reconResultId == null || !hasSelectedSurfaces(selectedSurfaceIds)) {
            return List.of();
        }
        List<ReconApplicationSurface> allSurfaces = applicationSurfaceRepository
                .findByReconResultIdAndDeletedFalseOrderByIdAsc(reconResultId);
        return filterSelectedSurfaces(
                reconResultId, allSurfaces, selectedSurfaceIds).stream()
                .filter(this::isSelectableCapability)
                .map(ReconApplicationSurface::getId)
                .toList();
    }

    public String loadTargetIntelligenceMemory(
            Long reconResultId,
            List<Long> selectedSurfaceIds) {
        if (reconResultId == null) {
            return null;
        }
        ReconResult result = reconResultRepository.findById(reconResultId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "ReconResult not found: " + reconResultId));
        List<ReconApplicationSurface> allSurfaces = applicationSurfaceRepository
                .findByReconResultIdAndDeletedFalseOrderByIdAsc(reconResultId);
        List<ReconApplicationSurface> surfaces = filterSelectedSurfaces(
                reconResultId, allSurfaces, selectedSurfaceIds);

        ObjectNode memory = objectMapper.createObjectNode();
        memory.put("source", "RECON_RESULT");
        memory.put("reconResultId", result.getId());
        memory.put("version", result.getVersion());
        memory.put("surfaceSelectionMode",
                hasSelectedSurfaces(selectedSurfaceIds) ? "SELECTED" : "ALL");
        ArrayNode selectedIds = memory.putArray("selectedSurfaceIds");
        if (hasSelectedSurfaces(selectedSurfaceIds)) {
            normalizeSelectedSurfaceIds(selectedSurfaceIds)
                    .forEach(selectedIds::add);
        }
        ArrayNode effectiveSelectedIds =
                memory.putArray("effectiveSelectedSurfaceIds");
        surfaces.stream()
                .filter(this::isSelectableCapability)
                .map(ReconApplicationSurface::getId)
                .forEach(effectiveSelectedIds::add);
        if (result.getQualityStatus() != null) {
            memory.put("qualityStatus", result.getQualityStatus().name());
        }
        putJsonOrText(memory, "currentResult", result.getCurrentResult());
        putJsonOrText(memory, "targetIntelligenceMemory",
                result.getTargetIntelligenceMemory());

        ArrayNode applicationSurfaces = memory.putArray("applicationSurfaces");
        Map<Long, ReconApplicationSurface> allById = new HashMap<>();
        allSurfaces.forEach(surface -> allById.put(surface.getId(), surface));
        for (ReconApplicationSurface surface : surfaces) {
            ObjectNode item = applicationSurfaces.addObject();
            item.put("id", surface.getId());
            item.put("parentSurfaceId", surface.getParentSurfaceId());
            item.put("capabilityKey", surface.getCapabilityKey());
            item.put("surfaceLevel", surface.getSurfaceLevel() != null
                    ? surface.getSurfaceLevel().name() : "CAPABILITY");
            item.put("selectable", surface.getSelectable() == null
                    || Boolean.TRUE.equals(surface.getSelectable()));
            ReconApplicationSurface parent =
                    allById.get(surface.getParentSurfaceId());
            if (parent != null) {
                item.put("parentTitle", parent.getCurrentTitle());
                item.put("parentCapabilityKey", parent.getCapabilityKey());
            }
            item.put("title", surface.getCurrentTitle());
            item.put("description", surface.getCurrentDescription());
            item.put("surfaceType", surface.getSurfaceType());
            item.put("relatedTool", surface.getRelatedTool());
            item.put("supportedActions", surface.getSupportedActions());
            item.put("resourceScope", surface.getResourceScope());
            if (surface.getEvidenceSource() != null) {
                item.put("evidenceSource", surface.getEvidenceSource().name());
            }
            if (surface.getVerificationStatus() != null) {
                item.put("verificationStatus", surface.getVerificationStatus().name());
            }
            item.put("sourceTraceIds", surface.getSourceTraceIds());
            if (surface.getSourceType() != null) {
                item.put("sourceType", surface.getSourceType().name());
            }
            item.put("userEdited", Boolean.TRUE.equals(surface.getUserEdited()));
        }
        return memory.toString();
    }

    private List<ReconApplicationSurface> filterSelectedSurfaces(
            Long reconResultId,
            List<ReconApplicationSurface> surfaces,
            List<Long> selectedSurfaceIds) {
        if (!hasSelectedSurfaces(selectedSurfaceIds)) {
            return surfaces;
        }
        Set<Long> selected = normalizeSelectedSurfaceIds(selectedSurfaceIds);
        Map<Long, ReconApplicationSurface> byId = new HashMap<>();
        for (ReconApplicationSurface surface : surfaces) {
            byId.put(surface.getId(), surface);
        }
        if (!byId.keySet().containsAll(selected)) {
            throw new IllegalArgumentException(
                    "selectedSurfaceIds must belong to active ReconResult surfaces: "
                            + reconResultId);
        }
        Set<Long> effectiveIds = new LinkedHashSet<>();
        for (Long selectedId : selected) {
            ReconApplicationSurface selectedSurface = byId.get(selectedId);
            if (selectedSurface.getSurfaceLevel()
                    == ReconApplicationSurface.SurfaceLevel.DOMAIN) {
                for (ReconApplicationSurface candidate : surfaces) {
                    if (selectedId.equals(candidate.getParentSurfaceId())
                            && isSelectableCapability(candidate)) {
                        effectiveIds.add(candidate.getId());
                    }
                }
            } else if (isSelectableCapability(selectedSurface)) {
                effectiveIds.add(selectedId);
            }
        }
        List<ReconApplicationSurface> filtered = new ArrayList<>();
        for (ReconApplicationSurface surface : surfaces) {
            if (effectiveIds.contains(surface.getId())) {
                filtered.add(surface);
            }
        }
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException(
                    "selectedSurfaceIds did not resolve to selectable capabilities");
        }
        return filtered;
    }

    private boolean hasSelectedSurfaces(List<Long> selectedSurfaceIds) {
        return selectedSurfaceIds != null && selectedSurfaceIds.stream()
                .anyMatch(id -> id != null && id > 0);
    }

    private Set<Long> normalizeSelectedSurfaceIds(List<Long> selectedSurfaceIds) {
        Set<Long> normalized = new LinkedHashSet<>();
        if (selectedSurfaceIds == null) {
            return normalized;
        }
        for (Long id : selectedSurfaceIds) {
            if (id != null && id > 0) {
                normalized.add(id);
            }
        }
        return normalized;
    }

    private boolean isSelectableCapability(ReconApplicationSurface surface) {
        boolean capability = surface.getSurfaceLevel() == null
                || surface.getSurfaceLevel()
                == ReconApplicationSurface.SurfaceLevel.CAPABILITY;
        return capability && (surface.getSelectable() == null
                || Boolean.TRUE.equals(surface.getSelectable()));
    }

    public void initializeSession(AgentSession session, Task task, String reconMemory) {
        if (task.getReconResultId() == null) {
            return;
        }
        session.setTargetIntelligenceMemory(reconMemory);
        if (Boolean.TRUE.equals(task.getSupplementalRecon())) {
            session.setCurrentPhase("RECON");
            session.setReconCompleted(false);
        } else {
            session.setCurrentPhase("BUILD");
            session.setReconCompleted(true);
        }
    }

    public String appendIntelligence(String baseMemory, String label, String intelligence) {
        if (intelligence == null || intelligence.isBlank()) {
            return baseMemory;
        }
        ObjectNode combined = objectMapper.createObjectNode();
        putJsonOrText(combined, "reconMemory", baseMemory);
        combined.put("additionalIntelligenceLabel", label);
        combined.put("additionalIntelligence", intelligence);
        return combined.toString();
    }

    private void putJsonOrText(ObjectNode parent, String fieldName, String value) {
        if (value == null || value.isBlank()) {
            parent.putNull(fieldName);
            return;
        }
        try {
            JsonNode parsed = objectMapper.readTree(value);
            parent.set(fieldName, parsed);
        } catch (Exception ignored) {
            parent.put(fieldName, value);
        }
    }
}
