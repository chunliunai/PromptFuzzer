package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptfuzzer.dto.CreateReconAttackUnitRequest;
import com.promptfuzzer.dto.ReconAttackUnitResponse;
import com.promptfuzzer.entity.ReconApplicationSurface;
import com.promptfuzzer.entity.ReconAttackUnit;
import com.promptfuzzer.entity.ReconResult;
import com.promptfuzzer.repository.ReconApplicationSurfaceRepository;
import com.promptfuzzer.repository.ReconAttackCandidateRepository;
import com.promptfuzzer.repository.ReconAttackUnitRepository;
import com.promptfuzzer.repository.ReconResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReconAttackUnitService {

    private final ReconAttackUnitRepository attackUnitRepository;
    private final ReconResultRepository resultRepository;
    private final ReconApplicationSurfaceRepository surfaceRepository;
    private final ReconAttackCandidateRepository candidateRepository;
    private final ReconAttackCandidatePromptService promptService;
    private final ObjectMapper objectMapper;

    public List<ReconAttackUnitResponse> generateAttackUnits(
            Long reconResultId,
            CreateReconAttackUnitRequest request) {
        int maxAttackUnits = normalizeMaxAttackUnits(
                request != null ? request.getMaxAttackUnits() : null);
        List<Long> surfaceIds = request != null ? request.getSurfaceIds() : null;
        return generateForBridge(reconResultId, surfaceIds, maxAttackUnits).stream()
                .map(ReconAttackUnitResponse::from)
                .collect(Collectors.toList());
    }

    public List<ReconAttackUnitResponse> listAttackUnits(Long reconResultId) {
        requireResult(reconResultId);
        List<ReconAttackUnit> units = attackUnitRepository
                .findByReconResultIdOrderByIdAsc(reconResultId);
        if (units.isEmpty()) {
            return List.of();
        }
        String latestGenerationId =
                units.get(units.size() - 1).getGenerationId();
        return units.stream()
                .filter(unit -> latestGenerationId == null
                        || latestGenerationId.equals(unit.getGenerationId()))
                .map(ReconAttackUnitResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteAttackUnit(Long attackUnitId) {
        ReconAttackUnit unit = attackUnitRepository.findById(attackUnitId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "ReconAttackUnit not found: " + attackUnitId));
        candidateRepository.deleteByAttackUnitId(attackUnitId);
        attackUnitRepository.delete(unit);
    }

    public List<ReconAttackUnit> generateForBridge(
            Long reconResultId,
            List<Long> requestedSurfaceIds,
            Integer requestedMaxAttackUnits) {
        ReconResult result = requireResult(reconResultId);
        List<ReconApplicationSurface> surfaces = selectSurfaces(
                reconResultId, requestedSurfaceIds);
        int maxAttackUnits = normalizeMaxAttackUnits(requestedMaxAttackUnits);

        JsonNode generated;
        try {
            generated = promptService.synthesizeAttackUnits(
                    buildReconResultJson(result),
                    buildSurfacesJson(surfaces),
                    maxAttackUnits);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to synthesize RECON attack units", e);
        }

        List<ReconAttackUnit> units = parseAttackUnits(
                generated, result, surfaces, maxAttackUnits);
        if (units.isEmpty()) {
            throw new IllegalStateException(
                    "RECON attack unit prompt returned no valid attack units");
        }
        String generationId = UUID.randomUUID().toString();
        units.forEach(unit -> unit.setGenerationId(generationId));
        return attackUnitRepository.saveAll(units);
    }

    public List<ReconAttackUnit> resolveForCandidateGeneration(
            Long reconResultId,
            List<Long> requestedAttackUnitIds,
            List<Long> requestedSurfaceIds,
            Integer requestedMaxAttackUnits) {
        Set<Long> attackUnitIds = normalizeIds(requestedAttackUnitIds);
        if (attackUnitIds.isEmpty()) {
            return generateForBridge(
                    reconResultId,
                    requestedSurfaceIds,
                    requestedMaxAttackUnits);
        }
        requireResult(reconResultId);
        List<ReconAttackUnit> units = attackUnitRepository.findAllById(
                attackUnitIds);
        Map<Long, ReconAttackUnit> byId = units.stream()
                .collect(Collectors.toMap(ReconAttackUnit::getId, unit -> unit));
        if (!byId.keySet().containsAll(attackUnitIds)
                || units.stream().anyMatch(unit ->
                        !reconResultId.equals(unit.getReconResultId()))) {
            throw new IllegalArgumentException(
                    "attackUnitIds must belong to ReconResult: "
                            + reconResultId);
        }
        return attackUnitIds.stream()
                .map(byId::get)
                .collect(Collectors.toList());
    }

    private List<ReconAttackUnit> parseAttackUnits(
            JsonNode generated,
            ReconResult result,
            List<ReconApplicationSurface> surfaces,
            int maxAttackUnits) {
        Map<Long, ReconApplicationSurface> byId = surfaces.stream()
                .collect(Collectors.toMap(
                        ReconApplicationSurface::getId,
                        surface -> surface,
                        (left, right) -> left,
                        LinkedHashMap::new));
        Map<Long, SurfaceRole> roles = surfaces.stream()
                .collect(Collectors.toMap(
                        ReconApplicationSurface::getId,
                        this::classifyRole));
        Set<Long> usedPrimaryIds = new LinkedHashSet<>();
        Set<String> usedUnitKeys = new LinkedHashSet<>();
        List<ReconAttackUnit> units = new ArrayList<>();
        JsonNode nodes = generated.path("attackUnits");
        if (!nodes.isArray()) {
            return units;
        }

        for (JsonNode node : nodes) {
            if (units.size() >= maxAttackUnits) {
                break;
            }
            List<Long> primaryIds = parseIds(node.path("primarySurfaceIds")).stream()
                    .filter(byId::containsKey)
                    .filter(id -> roles.get(id) == SurfaceRole.PRIMARY)
                    .filter(id -> !usedPrimaryIds.contains(id))
                    .collect(Collectors.toList());
            if (primaryIds.isEmpty()) {
                continue;
            }

            Set<Long> primaryIdSet = new LinkedHashSet<>(primaryIds);
            List<Long> supportingIds = parseIds(
                    node.path("supportingSurfaceIds")).stream()
                    .filter(byId::containsKey)
                    .filter(id -> roles.get(id) == SurfaceRole.SUPPORTING)
                    .filter(id -> !primaryIdSet.contains(id))
                    .collect(Collectors.toList());
            List<Long> negativeIds = parseIds(
                    node.path("negativeBoundarySurfaceIds")).stream()
                    .filter(byId::containsKey)
                    .filter(id -> roles.get(id) == SurfaceRole.NEGATIVE_BOUNDARY)
                    .filter(id -> !primaryIdSet.contains(id))
                    .collect(Collectors.toList());

            String title = text(node, "title");
            if (title.isBlank()) {
                continue;
            }
            String unitKey = uniqueUnitKey(
                    text(node, "unitKey"), usedUnitKeys, units.size() + 1);
            usedUnitKeys.add(unitKey);
            usedPrimaryIds.addAll(primaryIds);

            List<ReconApplicationSurface> snapshotSurfaces = new ArrayList<>();
            LinkedHashSet<Long> snapshotIds = new LinkedHashSet<>();
            snapshotIds.addAll(primaryIds);
            snapshotIds.addAll(supportingIds);
            snapshotIds.addAll(negativeIds);
            for (Long id : snapshotIds) {
                snapshotSurfaces.add(byId.get(id));
            }

            ReconAttackUnit unit = new ReconAttackUnit();
            unit.setReconResultId(result.getId());
            unit.setUnitKey(unitKey);
            unit.setTitle(title);
            unit.setDescription(text(node, "description"));
            unit.setPrimarySurfaceIds(json(primaryIds));
            unit.setSupportingSurfaceIds(json(supportingIds));
            unit.setNegativeBoundarySurfaceIds(json(negativeIds));
            unit.setResourceBoundary(text(node, "resourceBoundary"));
            unit.setSecurityControls(json(parseStrings(
                    node.path("securityControls"))));
            unit.setRiskDimensions(json(parseStrings(
                    node.path("riskDimensions"))));
            unit.setAggregationReason(text(node, "aggregationReason"));
            unit.setRiskLevel(parseRiskLevel(text(node, "riskLevel")));
            unit.setConfidence(resolveConfidence(
                    text(node, "confidence"), primaryIds, byId));
            unit.setSourceSurfaceSnapshot(
                    buildSurfacesJson(snapshotSurfaces));
            units.add(unit);
        }
        return units;
    }

    private ReconAttackUnit.ConfidenceLevel resolveConfidence(
            String requested,
            List<Long> primaryIds,
            Map<Long, ReconApplicationSurface> byId) {
        boolean allVerified = primaryIds.stream()
                .map(byId::get)
                .allMatch(surface -> surface.getVerificationStatus()
                        == ReconApplicationSurface.VerificationStatus.VERIFIED);
        boolean anyObserved = primaryIds.stream()
                .map(byId::get)
                .anyMatch(surface -> surface.getVerificationStatus()
                        == ReconApplicationSurface.VerificationStatus.VERIFIED
                        || surface.getVerificationStatus()
                        == ReconApplicationSurface.VerificationStatus
                        .PARTIALLY_VERIFIED);
        ReconAttackUnit.ConfidenceLevel parsed = parseConfidence(requested);
        if (allVerified) {
            return parsed;
        }
        if (anyObserved) {
            return parsed == ReconAttackUnit.ConfidenceLevel.HIGH
                    ? ReconAttackUnit.ConfidenceLevel.MEDIUM : parsed;
        }
        return ReconAttackUnit.ConfidenceLevel.LOW;
    }

    private List<ReconApplicationSurface> selectSurfaces(
            Long reconResultId,
            List<Long> requestedSurfaceIds) {
        List<ReconApplicationSurface> all = surfaceRepository
                .findByReconResultIdAndDeletedFalseOrderByIdAsc(reconResultId);
        if (all.isEmpty()) {
            throw new IllegalArgumentException(
                    "ReconResult has no active application surfaces: "
                            + reconResultId);
        }
        Set<Long> requested = normalizeIds(requestedSurfaceIds);
        if (requested.isEmpty()) {
            List<ReconApplicationSurface> selectable = all.stream()
                    .filter(this::isSelectableCapability)
                    .collect(Collectors.toList());
            if (selectable.isEmpty()) {
                throw new IllegalArgumentException(
                        "ReconResult has no selectable application surfaces: "
                                + reconResultId);
            }
            return selectable;
        }

        Map<Long, ReconApplicationSurface> byId = all.stream()
                .collect(Collectors.toMap(
                        ReconApplicationSurface::getId, surface -> surface));
        if (!byId.keySet().containsAll(requested)) {
            throw new IllegalArgumentException(
                    "surfaceIds must belong to active ReconResult surfaces: "
                            + reconResultId);
        }
        Set<Long> expandedIds = new LinkedHashSet<>();
        for (Long requestedId : requested) {
            ReconApplicationSurface surface = byId.get(requestedId);
            if (isDomain(surface)) {
                all.stream()
                        .filter(this::isSelectableCapability)
                        .filter(candidate -> requestedId.equals(
                                candidate.getParentSurfaceId()))
                        .map(ReconApplicationSurface::getId)
                        .forEach(expandedIds::add);
            } else if (isSelectableCapability(surface)) {
                expandedIds.add(requestedId);
            } else {
                throw new IllegalArgumentException(
                        "surfaceIds must reference selectable capabilities or domains: "
                                + requestedId);
            }
        }
        if (expandedIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Selected domains have no selectable capability surfaces");
        }
        return all.stream()
                .filter(surface -> expandedIds.contains(surface.getId()))
                .collect(Collectors.toList());
    }

    private String buildReconResultJson(ReconResult result) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", result.getId());
        node.put("qualityStatus", result.getQualityStatus().name());
        putJsonOrText(node, "currentResult", result.getCurrentResult());
        putJsonOrText(node, "targetIntelligenceMemory",
                result.getTargetIntelligenceMemory());
        putJsonOrText(node, "businessProfile", result.getBusinessProfile());
        putJsonOrText(node, "openDiscovery", result.getOpenDiscovery());
        putJsonOrText(node, "coverageMatrix", result.getCoverageMatrix());
        putJsonOrText(node, "capabilityFacts", result.getCapabilityFacts());
        putJsonOrText(node, "capabilityInventory",
                result.getCapabilityInventory());
        putJsonOrText(node, "toolInventory", result.getToolInventory());
        putJsonOrText(node, "unresolvedCapabilities",
                result.getUnresolvedCapabilities());
        return node.toString();
    }

    private String buildSurfacesJson(List<ReconApplicationSurface> surfaces) {
        ArrayNode array = objectMapper.createArrayNode();
        Map<Long, ReconApplicationSurface> allById = new HashMap<>();
        if (!surfaces.isEmpty()) {
            surfaceRepository
                    .findByReconResultIdAndDeletedFalseOrderByIdAsc(
                            surfaces.get(0).getReconResultId())
                    .forEach(surface -> allById.put(surface.getId(), surface));
        }
        for (ReconApplicationSurface surface : surfaces) {
            ObjectNode node = array.addObject();
            node.put("id", surface.getId());
            if (surface.getParentSurfaceId() != null) {
                node.put("parentSurfaceId", surface.getParentSurfaceId());
            }
            node.put("capabilityKey", surface.getCapabilityKey());
            ReconApplicationSurface parent =
                    allById.get(surface.getParentSurfaceId());
            if (parent != null) {
                node.put("parentTitle", parent.getCurrentTitle());
                node.put("parentCapabilityKey", parent.getCapabilityKey());
            }
            node.put("title", surface.getCurrentTitle());
            node.put("description", surface.getCurrentDescription());
            node.put("surfaceType", surface.getSurfaceType());
            node.put("relatedTool", surface.getRelatedTool());
            putJsonOrText(node, "supportedActions",
                    surface.getSupportedActions());
            node.put("resourceScope", surface.getResourceScope());
            node.put("evidenceSource", surface.getEvidenceSource() != null
                    ? surface.getEvidenceSource().name() : "");
            node.put("verificationStatus",
                    surface.getVerificationStatus() != null
                            ? surface.getVerificationStatus().name() : "");
            node.put("roleHint", classifyRole(surface).name());
        }
        return array.toString();
    }

    private SurfaceRole classifyRole(ReconApplicationSurface surface) {
        if (surface.getVerificationStatus()
                == ReconApplicationSurface.VerificationStatus.CONTRADICTED
                || explicitlyUnsupported(surface)) {
            return SurfaceRole.NEGATIVE_BOUNDARY;
        }
        String type = value(surface.getSurfaceType()).toUpperCase(Locale.ROOT);
        if ("UTILITY".equals(type)) {
            return SurfaceRole.UTILITY;
        }
        if ("ENVIRONMENT".equals(type)) {
            return SurfaceRole.SUPPORTING;
        }
        return SurfaceRole.PRIMARY;
    }

    private boolean explicitlyUnsupported(ReconApplicationSurface surface) {
        String title = value(surface.getCurrentTitle());
        String description = value(surface.getCurrentDescription()).trim();
        return title.contains("（不支持）")
                || title.contains("(不支持)")
                || description.startsWith("不支持")
                || description.startsWith("明确不支持");
    }

    private List<Long> parseIds(JsonNode node) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                long id = item.asLong(-1);
                if (id > 0) {
                    ids.add(id);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private List<String> parseStrings(JsonNode node) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asText("").trim();
                if (!value.isBlank()) {
                    values.add(value.toUpperCase(Locale.ROOT));
                }
                if (values.size() >= 8) {
                    break;
                }
            }
        }
        return new ArrayList<>(values);
    }

    private String uniqueUnitKey(
            String requested,
            Set<String> usedUnitKeys,
            int index) {
        String base = value(requested)
                .trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.isBlank()) {
            base = "ATTACK_UNIT_" + index;
        }
        String candidate = base;
        int suffix = 2;
        while (usedUnitKeys.contains(candidate)) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }

    private ReconAttackUnit.RiskLevel parseRiskLevel(String value) {
        try {
            return ReconAttackUnit.RiskLevel.valueOf(
                    value(value).trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return ReconAttackUnit.RiskLevel.MEDIUM;
        }
    }

    private ReconAttackUnit.ConfidenceLevel parseConfidence(String value) {
        try {
            return ReconAttackUnit.ConfidenceLevel.valueOf(
                    value(value).trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return ReconAttackUnit.ConfidenceLevel.LOW;
        }
    }

    private int normalizeMaxAttackUnits(Integer value) {
        if (value == null) {
            return 8;
        }
        return Math.max(1, Math.min(value, 8));
    }

    private Set<Long> normalizeIds(List<Long> ids) {
        Set<Long> normalized = new LinkedHashSet<>();
        if (ids == null) {
            return normalized;
        }
        for (Long id : ids) {
            if (id != null && id > 0) {
                normalized.add(id);
            }
        }
        return normalized;
    }

    private boolean isDomain(ReconApplicationSurface surface) {
        return surface.getSurfaceLevel()
                == ReconApplicationSurface.SurfaceLevel.DOMAIN;
    }

    private boolean isSelectableCapability(ReconApplicationSurface surface) {
        boolean capability = surface.getSurfaceLevel() == null
                || surface.getSurfaceLevel()
                == ReconApplicationSurface.SurfaceLevel.CAPABILITY;
        return capability && (surface.getSelectable() == null
                || Boolean.TRUE.equals(surface.getSelectable()));
    }

    private void putJsonOrText(ObjectNode parent, String fieldName, String value) {
        if (value == null || value.isBlank()) {
            parent.putNull(fieldName);
            return;
        }
        try {
            parent.set(fieldName, objectMapper.readTree(value));
        } catch (Exception ignored) {
            parent.put(fieldName, value);
        }
    }

    private ReconResult requireResult(Long reconResultId) {
        return resultRepository.findById(reconResultId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "ReconResult not found: " + reconResultId));
    }

    private String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize JSON", e);
        }
    }

    private enum SurfaceRole {
        PRIMARY, SUPPORTING, NEGATIVE_BOUNDARY, UTILITY
    }
}
