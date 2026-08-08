package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptfuzzer.entity.ReconTrace;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReconSurfaceQualityService {

    private final ObjectMapper objectMapper;

    public QualityResult validateAndNormalize(
            JsonNode synthesis,
            JsonNode evidenceSummary) {
        return validateAndNormalize(synthesis, evidenceSummary, List.of());
    }

    public QualityResult validateAndNormalize(
            JsonNode synthesis,
            JsonNode evidenceSummary,
            List<ReconTrace> traces) {
        List<String> issues = new ArrayList<>();
        Map<String, String> evidenceByCapability =
                evidenceByCapability(
                        evidenceSummary.path("capabilityFacts"), traces, issues);
        ArrayNode normalized = objectMapper.createArrayNode();
        Set<String> keys = new LinkedHashSet<>();
        Set<String> titles = new HashSet<>();
        Set<String> mappedCapabilityKeys = new HashSet<>();

        JsonNode source = synthesis.path("applicationSurfaces");
        if (!source.isArray()) {
            issues.add("applicationSurfaces is not an array");
            return new QualityResult(normalized, issues);
        }

        for (JsonNode node : source) {
            if (!node.isObject()) {
                issues.add("Ignored non-object application surface");
                continue;
            }
            ObjectNode item = ((ObjectNode) node).deepCopy();
            String key = normalizeKey(item.path("surfaceKey").asText(""));
            String title = item.path("title").asText("").trim();
            if (key.isBlank() || title.isBlank()) {
                issues.add("Ignored surface without surfaceKey or title");
                continue;
            }
            if (!keys.add(key)) {
                issues.add("Ignored duplicate surfaceKey: " + key);
                continue;
            }
            String normalizedTitle = title.toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", "");
            if (!titles.add(normalizedTitle)) {
                issues.add("Ignored duplicate surface title: " + title);
                continue;
            }

            String level = item.path("surfaceLevel").asText("CAPABILITY")
                    .trim().toUpperCase(Locale.ROOT);
            if (!level.equals("DOMAIN") && !level.equals("CAPABILITY")) {
                level = "CAPABILITY";
                issues.add("Normalized invalid surfaceLevel for " + key);
            }
            item.put("surfaceKey", key);
            item.put("surfaceLevel", level);
            if (level.equals("DOMAIN")) {
                item.put("parentSurfaceKey", "");
                item.put("selectable", false);
            } else {
                item.put("selectable", true);
                List<String> capabilityKeys = textArray(item.path("capabilityKeys"));
                if (capabilityKeys.isEmpty()) {
                    issues.add("Ignored capability without capabilityKeys: " + key);
                    continue;
                }
                List<String> missingFacts = capabilityKeys.stream()
                        .map(this::normalizeKey)
                        .filter(capabilityKey ->
                                !evidenceByCapability.containsKey(capabilityKey))
                        .toList();
                if (!missingFacts.isEmpty()) {
                    issues.add("Ignored capability with missing facts: " + key
                            + " -> " + String.join(",", missingFacts));
                    continue;
                }
                boolean duplicateMapping = capabilityKeys.stream()
                        .map(this::normalizeKey)
                        .allMatch(mappedCapabilityKeys::contains);
                if (duplicateMapping) {
                    issues.add("Ignored duplicate capability mapping: " + key);
                    continue;
                }
                capabilityKeys.stream()
                        .map(this::normalizeKey)
                        .forEach(mappedCapabilityKeys::add);
                applyEvidence(item, capabilityKeys, evidenceByCapability, issues);
            }
            normalized.add(item);
        }

        Set<String> domainKeys = new HashSet<>();
        normalized.forEach(node -> {
            if ("DOMAIN".equals(node.path("surfaceLevel").asText())) {
                domainKeys.add(node.path("surfaceKey").asText());
            }
        });
        ArrayNode hierarchyChecked = objectMapper.createArrayNode();
        for (JsonNode node : normalized) {
            ObjectNode item = (ObjectNode) node;
            if ("CAPABILITY".equals(item.path("surfaceLevel").asText())) {
                String parentKey = normalizeKey(
                        item.path("parentSurfaceKey").asText(""));
                if (parentKey.isBlank() || !domainKeys.contains(parentKey)) {
                    issues.add("Ignored capability with invalid parent: "
                            + item.path("surfaceKey").asText());
                    continue;
                }
                item.put("parentSurfaceKey", parentKey);
            }
            hierarchyChecked.add(item);
        }
        Map<String, List<ObjectNode>> childrenByDomain = new HashMap<>();
        for (JsonNode node : hierarchyChecked) {
            if ("CAPABILITY".equals(node.path("surfaceLevel").asText())) {
                ObjectNode child = (ObjectNode) node;
                childrenByDomain.computeIfAbsent(
                        child.path("parentSurfaceKey").asText(),
                        ignored -> new ArrayList<>()).add(child);
            }
        }

        ArrayNode finalSurfaces = objectMapper.createArrayNode();
        Set<String> retainedDomains = new HashSet<>();
        for (JsonNode node : hierarchyChecked) {
            if (!"DOMAIN".equals(node.path("surfaceLevel").asText())) {
                continue;
            }
            ObjectNode domain = (ObjectNode) node;
            String domainKey = domain.path("surfaceKey").asText();
            List<ObjectNode> children =
                    childrenByDomain.getOrDefault(domainKey, List.of());
            if (children.isEmpty()) {
                issues.add("Ignored DOMAIN without CAPABILITY children: "
                        + domainKey);
                continue;
            }
            deriveDomainEvidence(domain, children);
            retainedDomains.add(domainKey);
            finalSurfaces.add(domain);
        }
        for (JsonNode node : hierarchyChecked) {
            if ("CAPABILITY".equals(node.path("surfaceLevel").asText())
                    && retainedDomains.contains(
                    node.path("parentSurfaceKey").asText())) {
                finalSurfaces.add(node);
            }
        }
        return new QualityResult(finalSurfaces, issues);
    }

    private Map<String, String> evidenceByCapability(
            JsonNode facts,
            List<ReconTrace> traces,
            List<String> issues) {
        Map<String, String> evidence = new HashMap<>();
        Map<Long, String> traceResponses = traceResponses(traces);
        if (!facts.isArray()) {
            return evidence;
        }
        for (JsonNode fact : facts) {
            String key = normalizeKey(fact.path("capabilityKey").asText(""));
            String level = fact.path("evidenceLevel").asText("CLAIMED")
                    .trim().toUpperCase(Locale.ROOT);
            if (!key.isBlank()) {
                level = validateStructuredEvidence(
                        key, level, fact.path("sourceTraceIds"),
                        traceResponses, issues);
                evidence.put(key, level);
            }
        }
        return evidence;
    }

    private Map<Long, String> traceResponses(List<ReconTrace> traces) {
        Map<Long, String> responses = new HashMap<>();
        if (traces == null) {
            return responses;
        }
        for (ReconTrace trace : traces) {
            if (trace == null || trace.getId() == null) {
                continue;
            }
            String response = trace.getExtractedText();
            if (response == null || response.isBlank()) {
                response = trace.getRawResponse();
            }
            responses.put(trace.getId(), response != null ? response : "");
        }
        return responses;
    }

    private String validateStructuredEvidence(
            String capabilityKey,
            String evidenceLevel,
            JsonNode sourceTraceIds,
            Map<Long, String> traceResponses,
            List<String> issues) {
        if (!"RESULT_OBSERVED".equals(evidenceLevel)
                || traceResponses.isEmpty()) {
            return evidenceLevel;
        }
        String upperKey = capabilityKey.toUpperCase(Locale.ROOT);
        boolean requiresStructuredValidation =
                upperKey.contains("MARKDOWN_TABLE")
                        || upperKey.contains("VEGA_LITE")
                        || upperKey.contains("HTML_REPORT")
                        || upperKey.endsWith(".JSON")
                        || upperKey.contains("JSON_OUTPUT");
        if (!requiresStructuredValidation) {
            return evidenceLevel;
        }
        String response = combinedResponses(sourceTraceIds, traceResponses);
        if (response.isBlank()) {
            return downgradeStructuredEvidence(
                    capabilityKey, "source Trace has no response", issues);
        }

        String lowerResponse = response.toLowerCase(Locale.ROOT);
        if (upperKey.contains("MARKDOWN_TABLE")
                && !isMarkdownTable(response)) {
            return downgradeStructuredEvidence(
                    capabilityKey, "response is not a Markdown table", issues);
        }
        if (upperKey.contains("VEGA_LITE")
                && !(lowerResponse.contains("vega-lite")
                && lowerResponse.contains("\"encoding\"")
                && lowerResponse.contains("\"data\""))) {
            return downgradeStructuredEvidence(
                    capabilityKey, "response is not a complete Vega-Lite spec",
                    issues);
        }
        if (upperKey.contains("HTML_REPORT")
                && !(lowerResponse.contains("<html")
                || lowerResponse.contains("<body")
                || lowerResponse.contains("<!doctype"))) {
            return downgradeStructuredEvidence(
                    capabilityKey, "response is not an HTML document", issues);
        }
        if ((upperKey.endsWith(".JSON") || upperKey.contains("JSON_OUTPUT"))
                && !containsValidJson(response)) {
            return downgradeStructuredEvidence(
                    capabilityKey, "response does not contain valid JSON", issues);
        }
        return evidenceLevel;
    }

    private String combinedResponses(
            JsonNode sourceTraceIds,
            Map<Long, String> traceResponses) {
        if (!sourceTraceIds.isArray()) {
            return "";
        }
        StringBuilder combined = new StringBuilder();
        for (JsonNode traceId : sourceTraceIds) {
            long id;
            try {
                id = Long.parseLong(traceId.asText());
            } catch (Exception ignored) {
                continue;
            }
            String response = traceResponses.get(id);
            if (response != null && !response.isBlank()) {
                if (!combined.isEmpty()) {
                    combined.append('\n');
                }
                combined.append(response);
            }
        }
        return combined.toString();
    }

    private String downgradeStructuredEvidence(
            String capabilityKey,
            String reason,
            List<String> issues) {
        issues.add("Downgraded structured evidence for " + capabilityKey
                + ": " + reason);
        return "PARTIALLY_OBSERVED";
    }

    private boolean isMarkdownTable(String response) {
        boolean hasRow = false;
        boolean hasSeparator = false;
        for (String line : response.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.contains("|")) {
                hasRow = true;
            }
            if (trimmed.contains("|")
                    && trimmed.matches(".*:?-{3,}:?.*")) {
                hasSeparator = true;
            }
        }
        return hasRow && hasSeparator;
    }

    private boolean containsValidJson(String response) {
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return false;
        }
        try {
            objectMapper.readTree(response.substring(start, end + 1));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void deriveDomainEvidence(
            ObjectNode domain,
            List<ObjectNode> children) {
        int verified = 0;
        int partial = 0;
        int contradicted = 0;
        boolean observed = false;
        boolean external = false;
        Set<String> sourceTraceIds = new LinkedHashSet<>();

        for (ObjectNode child : children) {
            String status = child.path("verificationStatus").asText();
            if ("VERIFIED".equals(status)) {
                verified++;
            } else if ("PARTIALLY_VERIFIED".equals(status)) {
                partial++;
            } else if ("CONTRADICTED".equals(status)) {
                contradicted++;
            }
            String evidenceSource = child.path("evidenceSource").asText();
            observed |= "OBSERVED".equals(evidenceSource);
            external |= "EXTERNAL_EVIDENCE".equals(evidenceSource);
            sourceTraceIds.addAll(textArray(child.path("sourceTraceIds")));
        }

        if (verified == children.size()) {
            domain.put("verificationStatus", "VERIFIED");
        } else if (verified > 0 || partial > 0) {
            domain.put("verificationStatus", "PARTIALLY_VERIFIED");
        } else if (contradicted == children.size()) {
            domain.put("verificationStatus", "CONTRADICTED");
        } else {
            domain.put("verificationStatus", "UNVERIFIED");
        }
        domain.put("evidenceSource", observed
                ? "OBSERVED"
                : external ? "EXTERNAL_EVIDENCE" : "AGENT_CLAIM");
        domain.set("sourceTraceIds",
                objectMapper.valueToTree(sourceTraceIds));
    }

    private void applyEvidence(
            ObjectNode item,
            List<String> capabilityKeys,
            Map<String, String> evidenceByCapability,
            List<String> issues) {
        int strongest = 0;
        String strongestLevel = "CLAIMED";
        for (String capabilityKey : capabilityKeys) {
            String level = evidenceByCapability.get(normalizeKey(capabilityKey));
            if (level == null) {
                issues.add("Missing capability fact for " + capabilityKey);
                continue;
            }
            int score = evidenceScore(level);
            if (score > strongest) {
                strongest = score;
                strongestLevel = level;
            }
        }

        switch (strongestLevel) {
            case "RESULT_OBSERVED", "TOOL_OBSERVED" -> {
                item.put("evidenceSource", "OBSERVED");
                item.put("verificationStatus", "VERIFIED");
            }
            case "PARTIALLY_OBSERVED" -> {
                item.put("evidenceSource", "OBSERVED");
                item.put("verificationStatus", "PARTIALLY_VERIFIED");
            }
            case "CONTRADICTED" -> {
                item.put("evidenceSource", "AGENT_CLAIM");
                item.put("verificationStatus", "CONTRADICTED");
            }
            case "EXTERNAL_EVIDENCE" -> {
                item.put("evidenceSource", "EXTERNAL_EVIDENCE");
                item.put("verificationStatus", "UNVERIFIED");
            }
            default -> {
                item.put("evidenceSource", "AGENT_CLAIM");
                item.put("verificationStatus", "UNVERIFIED");
            }
        }
    }

    private int evidenceScore(String level) {
        return switch (level) {
            case "RESULT_OBSERVED" -> 6;
            case "TOOL_OBSERVED" -> 5;
            case "PARTIALLY_OBSERVED" -> 4;
            case "CONTRADICTED" -> 3;
            case "EXTERNAL_EVIDENCE" -> 2;
            default -> 1;
        };
    }

    private List<String> textArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (!node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9._-]", "_");
    }

    public record QualityResult(
            ArrayNode applicationSurfaces,
            List<String> issues) {
    }
}
