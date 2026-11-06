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
import java.util.LinkedHashMap;
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
        if (!source.isArray() && synthesis.path("surfaceCandidates").isArray()) {
            source = applicationSurfacesFromCandidates(
                    synthesis.path("surfaceCandidates"),
                    evidenceByCapability,
                    issues);
            issues.add("Normalized surfaceCandidates into applicationSurfaces");
        }
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
            if (isFormattingOnlySurface(
                    item.path("surfaceType").asText(""),
                    item.path("resourceScope").asText(""),
                    item.path("description").asText(""),
                    item.path("title").asText(""))) {
                issues.add("Ignored formatting-only surface: " + key);
                continue;
            }
            item.put("surfaceKey", key);
            item.put("surfaceLevel", level);
            if (level.equals("DOMAIN")) {
                item.put("parentSurfaceKey", "");
                item.put("selectable", false);
            } else {
                item.put("selectable",
                        !item.path("controlBoundarySurface").asBoolean(false));
                List<String> capabilityKeys = textArray(item.path("capabilityKeys"));
                if (capabilityKeys.isEmpty()) {
                    issues.add("Ignored capability without capabilityKeys: " + key);
                    continue;
                }
                List<String> normalizedCapabilityKeys = capabilityKeys.stream()
                        .map(this::normalizeKey)
                        .distinct()
                        .toList();
                List<String> validCapabilityKeys = normalizedCapabilityKeys.stream()
                        .filter(evidenceByCapability::containsKey)
                        .toList();
                List<String> missingFacts = normalizedCapabilityKeys.stream()
                        .filter(capabilityKey ->
                                !evidenceByCapability.containsKey(capabilityKey))
                        .toList();
                if (!missingFacts.isEmpty()) {
                    issues.add("Capability references missing facts: " + key
                            + " -> " + String.join(",", missingFacts));
                }
                item.set("capabilityKeys",
                        objectMapper.valueToTree(normalizedCapabilityKeys));
                boolean duplicateMapping = !validCapabilityKeys.isEmpty()
                        && validCapabilityKeys.stream()
                        .allMatch(mappedCapabilityKeys::contains);
                if (duplicateMapping) {
                    issues.add("Ignored duplicate capability mapping: " + key);
                    continue;
                }
                validCapabilityKeys.forEach(mappedCapabilityKeys::add);
                applyEvidenceWithFallback(item, item, validCapabilityKeys,
                        evidenceByCapability, issues);
            }
            preserveStructuredFields(item);
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

    private ArrayNode applicationSurfacesFromCandidates(
            JsonNode candidates,
            Map<String, String> evidenceByCapability,
            List<String> issues) {
        ArrayNode surfaces = objectMapper.createArrayNode();
        Map<String, ObjectNode> domains = new LinkedHashMap<>();
        Map<String, Integer> capabilityCounters = new HashMap<>();

        for (JsonNode candidate : candidates) {
            if (!candidate.isObject()) {
                issues.add("Ignored non-object surfaceCandidate");
                continue;
            }
            List<String> capabilityKeys = normalizeTextArray(
                    candidate.path("capabilityKeys"));
            if (capabilityKeys.isEmpty()) {
                capabilityKeys = normalizeTextArray(
                        candidate.path("capability_keys"));
            }
            if (capabilityKeys.isEmpty()) {
                String singleKey = candidate.path("capabilityKey").asText("");
                if (!singleKey.isBlank()) {
                    capabilityKeys = List.of(normalizeKey(singleKey));
                }
            }
            if (capabilityKeys.isEmpty()) {
                issues.add("Ignored surfaceCandidate without capabilityKeys");
                continue;
            }
            List<String> validCapabilityKeys = capabilityKeys.stream()
                    .map(this::normalizeKey)
                    .filter(capabilityKey ->
                            evidenceByCapability.containsKey(capabilityKey))
                    .distinct()
                    .toList();
            List<String> missingCapabilityKeys = capabilityKeys.stream()
                    .map(this::normalizeKey)
                    .filter(capabilityKey ->
                            !evidenceByCapability.containsKey(capabilityKey))
                    .toList();
            if (!missingCapabilityKeys.isEmpty()) {
                issues.add("SurfaceCandidate references capabilityKeys with "
                        + "missing facts: "
                        + String.join(",", missingCapabilityKeys));
            }
            List<String> normalizedCapabilityKeys = capabilityKeys.stream()
                    .map(this::normalizeKey)
                    .distinct()
                    .toList();

            String family = normalizeFamily(candidate.path("family").asText(""));
            String resourceDomain = normalizeSlug(firstNonBlank(
                    candidate.path("resourceDomain").asText(""),
                    candidate.path("resource_domain").asText(""),
                    inferResourceDomainFromMentions(candidate),
                    inferResourceDomain(family, normalizedCapabilityKeys)));
            String normalizedResourceDomain = normalizeResourceDomainByFamily(
                    family, resourceDomain, normalizedCapabilityKeys);
            String boundaryGroup = normalizeBoundaryGroup(firstNonBlank(
                    join(candidate.path("rawBoundaryMentions")),
                    join(candidate.path("raw_boundary_mentions")),
                    join(candidate.path("permissionBoundaries")),
                    join(candidate.path("permission_boundaries"))));
            String domainKey = normalizeKey(
                    family + "." + normalizedResourceDomain + "." + boundaryGroup);
            if (isNoiseCandidate(family, normalizedResourceDomain, candidate)
                    || isFormattingOnlyCandidate(
                    family, normalizedResourceDomain, candidate)) {
                issues.add("Ignored noise surfaceCandidate: " + domainKey);
                continue;
            }
            ObjectNode domain = domains.computeIfAbsent(domainKey, key -> {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("surfaceKey", key);
                node.put("parentSurfaceKey", "");
                node.put("surfaceLevel", "DOMAIN");
                node.put("selectable", false);
                node.put("title", preferredDomainTitle(
                        family, normalizedResourceDomain, candidate, issues));
                node.put("description", domainDescription(
                        candidate, family, normalizedResourceDomain, boundaryGroup));
                node.put("surfaceType", family);
                node.put("relatedTool", "");
                node.set("supportedActions", objectMapper.createArrayNode());
                node.put("resourceScope", resourceScope(candidate, boundaryGroup));
                node.set("resourceObjects", objectMapper.createArrayNode());
                node.set("entrypoints", objectMapper.createArrayNode());
                node.set("permissionBoundaries", objectMapper.createArrayNode());
                node.set("controlBoundaries", objectMapper.createArrayNode());
                node.set("normalizedFeatures", normalizedFeatures(
                        candidate, family, normalizedResourceDomain,
                        boundaryGroup));
                node.put("evidenceSource", "AGENT_CLAIM");
                node.put("verificationStatus", "UNVERIFIED");
                node.set("sourceTraceIds", objectMapper.createArrayNode());
                node.set("capabilityKeys", objectMapper.createArrayNode());
                surfaces.add(node);
                return node;
            });

            int next = capabilityCounters.merge(domainKey, 1, Integer::sum);
            ObjectNode capability = objectMapper.createObjectNode();
            capability.put("surfaceKey", normalizeKey(domainKey + ".C" + next));
            capability.put("parentSurfaceKey", domain.path("surfaceKey").asText());
            capability.put("surfaceLevel", "CAPABILITY");
            boolean controlBoundary = isControlBoundaryCandidate(
                    family, normalizedResourceDomain, candidate);
            capability.put("selectable", !controlBoundary);
            capability.put("controlBoundarySurface", controlBoundary);
            capability.put("title", preferredCapabilityTitle(
                    family, normalizedResourceDomain, candidate, issues));
            String description = capabilityDescription(candidate);
            capability.put("displayDescription", description);
            capability.put("description", description);
            capability.put("surfaceType", family);
            capability.put("relatedTool", relatedTool(candidate));
            capability.set("supportedActions",
                    objectMapper.valueToTree(actionLabels(actionMentions(candidate))));
            capability.put("resourceScope", resourceScope(candidate, boundaryGroup));
            capability.set("resourceObjects", normalizeArray(
                    objectMentions(candidate)));
            capability.set("entrypoints", normalizeArray(
                    entryMentions(candidate)));
            capability.set("permissionBoundaries", normalizeArray(
                    boundaryMentions(candidate)));
            capability.set("controlBoundaries", normalizeArray(
                    controlMentions(candidate)));
            capability.set("normalizedFeatures", normalizedFeatures(
                    candidate, family, normalizedResourceDomain,
                    boundaryGroup));
            capability.put("evidenceSource", "AGENT_CLAIM");
            capability.put("verificationStatus", "UNVERIFIED");
            capability.set("sourceTraceIds", normalizeArray(firstArray(
                    candidate.path("sourceTraceIds"),
                    candidate.path("source_trace_ids"))));
            capability.set("capabilityKeys",
                    objectMapper.valueToTree(normalizedCapabilityKeys));
            applyEvidenceWithFallback(capability, candidate, validCapabilityKeys,
                    evidenceByCapability, issues);
            surfaces.add(capability);
        }
        return surfaces;
    }

    private String normalizeFamily(String value) {
        String family = normalizeKey(value);
        return switch (family) {
            case "DATA_QUERY", "QUERY_INTERPRETATION",
                    "METADATA_DISCOVERY", "FILE_PROCESSING",
                    "CODE_EXECUTION", "TOOL_ORCHESTRATION",
                    "REPORT_RENDERING", "IMAGE_GENERATION",
                    "EXTERNAL_INTEGRATION", "STATE_MUTATION",
                    "AUTH_WORKFLOW" -> family;
            default -> "OTHER";
        };
    }

    private String normalizeSlug(String value) {
        String slug = value == null ? "" : value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return slug.isBlank() ? "general" : slug;
    }

    private String normalizeBoundaryGroup(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (normalized.contains("tenant") || normalized.contains("租户")) {
            return "tenant_scope";
        }
        if (normalized.contains("merchant") || normalized.contains("seller")
                || normalized.contains("shop") || normalized.contains("商家")) {
            return "merchant_scope";
        }
        if (normalized.contains("owner") || normalized.contains("account")
                || normalized.contains("账号") || normalized.contains("主体")) {
            return "owner_scope";
        }
        if (normalized.contains("path") || normalized.contains("file")
                || normalized.contains("workspace") || normalized.contains("路径")
                || normalized.contains("文件")) {
            return "filesystem_scope";
        }
        if (normalized.contains("role") || normalized.contains("permission")
                || normalized.contains("权限") || normalized.contains("角色")) {
            return "permission_scope";
        }
        if (normalized.contains("tool") || normalized.contains("skill")
                || normalized.contains("mcp") || normalized.contains("工具")) {
            return "tool_scope";
        }
        return "declared_scope";
    }

    private String inferResourceDomain(
            String family,
            List<String> capabilityKeys) {
        String joined = String.join(" ", capabilityKeys)
                .toUpperCase(Locale.ROOT);
        if (joined.contains("ORDER")) {
            return "order";
        }
        if (joined.contains("AFTERSALE") || joined.contains("AFTER_SALE")) {
            return "aftersale";
        }
        if (joined.contains("CREATOR") || joined.contains("达人")) {
            return "creator";
        }
        if (joined.contains("AD") || joined.contains("ADS")) {
            return "ads";
        }
        if (joined.contains("IMAGE") || joined.contains("VISUAL")
                || "IMAGE_GENERATION".equals(family)) {
            return "image_generation";
        }
        if (joined.contains("METADATA") || joined.contains("SCHEMA")
                || joined.contains("SQL") || joined.contains("指标")
                || "METADATA_DISCOVERY".equals(family)) {
            return "metadata";
        }
        if (joined.contains("FILE") || "FILE_PROCESSING".equals(family)) {
            return "file";
        }
        if (joined.contains("SANDBOX") || joined.contains("CODE")
                || "CODE_EXECUTION".equals(family)) {
            return "sandbox_fs";
        }
        if (joined.contains("REPORT") || joined.contains("RENDER")
                || "REPORT_RENDERING".equals(family)) {
            return "report";
        }
        if (joined.contains("SKILL") || joined.contains("MCP")
                || "TOOL_ORCHESTRATION".equals(family)) {
            return "business_tool";
        }
        return "general";
    }

    private String inferResourceDomainFromMentions(JsonNode candidate) {
        String text = (
                readableList(objectMentions(candidate)) + " "
                        + readableList(entryMentions(candidate)) + " "
                        + readableList(sinkMentions(candidate)))
                .toLowerCase(Locale.ROOT);
        if (text.contains("订单") || text.contains("order")) {
            return "order";
        }
        if (text.contains("售后") || text.contains("aftersale")
                || text.contains("after sale")) {
            return "aftersale";
        }
        if (text.contains("物流") || text.contains("logistics")) {
            return "logistics";
        }
        if (text.contains("达人") || text.contains("creator")) {
            return "creator";
        }
        if (text.contains("广告") || text.contains("ads")
                || text.contains("ad ")) {
            return "ads";
        }
        if (text.contains("指标") || text.contains("口径")
                || text.contains("metadata") || text.contains("schema")
                || text.contains("table") || text.contains("sql")) {
            return "metadata";
        }
        if (text.contains("文件") || text.contains("csv")
                || text.contains("xlsx") || text.contains("upload")
                || text.contains("download")) {
            return "file";
        }
        if (text.contains("沙箱") || text.contains("sandbox")
                || text.contains("python") || text.contains("bash")) {
            return "sandbox_fs";
        }
        if (text.contains("图片") || text.contains("图像")
                || text.contains("文生图") || text.contains("image")
                || text.contains("visual")) {
            return "image_generation";
        }
        if (text.contains("报告") || text.contains("report")
                || text.contains("html") || text.contains("markdown")) {
            return "report";
        }
        if (text.contains("工具") || text.contains("tool")
                || text.contains("mcp") || text.contains("skill")) {
            return "business_tool";
        }
        return "";
    }

    private String normalizeResourceDomainByFamily(
            String family,
            String resourceDomain,
            List<String> capabilityKeys) {
        String inferred = inferResourceDomain(family, capabilityKeys);
        return switch (family) {
            case "DATA_QUERY", "QUERY_INTERPRETATION" -> switch (resourceDomain) {
                case "business_tool", "general" -> "business_data";
                case "business_metric", "metric", "metrics" -> "business_metric";
                default -> resourceDomain;
            };
            case "METADATA_DISCOVERY" -> "metadata";
            case "FILE_PROCESSING" -> "file";
            case "CODE_EXECUTION" -> "sandbox_fs";
            case "TOOL_ORCHESTRATION" -> "business_tool";
            case "REPORT_RENDERING" -> "report";
            case "IMAGE_GENERATION" -> "image_generation";
            default -> "general".equals(resourceDomain) ? inferred : resourceDomain;
        };
    }

    private String domainTitle(
            String family,
            String resourceDomain,
            JsonNode candidate) {
        String resource = titleResourceLabel(resourceDomain, candidate);
        if (isReactLoopResource(resourceDomain)) {
            return "ReAct Observation 信任边界";
        }
        if (isSessionStateResource(resourceDomain)) {
            return "多轮会话状态边界";
        }
        return switch (family) {
            case "DATA_QUERY", "QUERY_INTERPRETATION" -> switch (resourceDomain) {
                case "business_data" -> "业务数据查询面";
                case "business_metric" -> "业务指标查询面";
                default -> resource + "数据查询面";
            };
            case "METADATA_DISCOVERY" -> "metadata".equals(resourceDomain)
                    ? "元数据与指标口径查询面"
                    : resource + "元数据与口径查询面";
            case "FILE_PROCESSING" -> "file".equals(resourceDomain)
                    ? "文件处理面"
                    : resource + "处理面";
            case "CODE_EXECUTION" -> "sandbox_fs".equals(resourceDomain)
                    ? "沙箱代码执行面"
                    : resource + "代码执行与运行边界";
            case "TOOL_ORCHESTRATION" -> "business_tool".equals(resourceDomain)
                    ? "业务工具编排边界"
                    : resource + "编排边界";
            case "REPORT_RENDERING" -> "report".equals(resourceDomain)
                    ? "报告渲染与产物边界"
                    : resource + "渲染与产物边界";
            case "IMAGE_GENERATION" -> "图片生成内容边界";
            case "EXTERNAL_INTEGRATION" -> resource + "外部集成边界";
            case "STATE_MUTATION" -> isActionLikeResource(resourceDomain)
                    ? resource + "操作面"
                    : resource + "状态变更操作面";
            case "AUTH_WORKFLOW" -> "auth".equals(resourceDomain)
                    ? "身份与授权流程面"
                    : resource + "授权流程面";
            default -> resource + "应用面";
        };
    }

    private String preferredDomainTitle(
            String family,
            String resourceDomain,
            JsonNode candidate,
            List<String> issues) {
        String fallback = domainTitle(family, resourceDomain, candidate);
        return cleanAiTitle(firstNonBlank(
                candidate.path("domainTitle").asText(""),
                candidate.path("domain_title").asText(""),
                candidate.path("displayDomainTitle").asText(""),
                candidate.path("display_domain_title").asText("")),
                fallback, "domain", issues);
    }

    private String preferredCapabilityTitle(
            String family,
            String resourceDomain,
            JsonNode candidate,
            List<String> issues) {
        String fallback = capabilityTitle(family, resourceDomain, candidate);
        return cleanAiTitle(firstNonBlank(
                candidate.path("displayTitle").asText(""),
                candidate.path("display_title").asText(""),
                candidate.path("title").asText("")),
                fallback, "capability", issues);
    }

    private String cleanAiTitle(
            String value,
            String fallback,
            String scope,
            List<String> issues) {
        String title = value == null ? "" : value.trim()
                .replaceAll("[`\"'“”‘’]", "")
                .replaceAll("\\s+", "");
        if (title.isBlank()) {
            return fallback;
        }
        String lower = title.toLowerCase(Locale.ROOT);
        boolean invalid = title.length() > 28
                || title.contains("\n")
                || lower.contains("calls")
                || lower.contains("e.g.")
                || lower.contains("example")
                || lower.contains("测试入口")
                || lower.contains("资源对象")
                || lower.contains("权限边界")
                || lower.contains("控制边界")
                || lower.matches(".*[a-z_]+\\([^)]*\\).*")
                || lower.matches(".*\\b(get|query|fetch|retrieve|call)[a-z0-9_]*_.*");
        if (invalid) {
            issues.add("Ignored low-quality AI " + scope + " title: " + value);
            return fallback;
        }
        return title;
    }

    private String capabilityTitle(
            String family,
            String resourceDomain,
            JsonNode candidate) {
        String resource = titleResourceLabel(resourceDomain, candidate);
        String actions = readableActionList(actionMentions(candidate));
        if (isReactLoopResource(resourceDomain)) {
            return actions.isBlank()
                    ? "ReAct Observation 注入"
                    : "ReAct Observation " + actions;
        }
        if (isSessionStateResource(resourceDomain)) {
            return actions.isBlank()
                    ? "多轮会话状态"
                    : "多轮会话状态" + actions;
        }
        if ("CODE_EXECUTION".equals(family)
                && "sandbox_fs".equals(resourceDomain)) {
            return actions.isBlank() ? "沙箱代码执行" : "沙箱代码" + actions;
        }
        if ("IMAGE_GENERATION".equals(family)
                && "image_generation".equals(resourceDomain)) {
            return actions.isBlank() || "图片生成".equals(actions)
                    ? "图片生成"
                    : "图片生成" + actions;
        }
        if ("DATA_QUERY".equals(family)
                || "QUERY_INTERPRETATION".equals(family)) {
            return resource + queryCapabilityAction(actions);
        }
        if ("REPORT_RENDERING".equals(family)
                && "report".equals(resourceDomain)) {
            if (actions.contains("生成图表") || actions.contains("生成")) {
                return "图表配置生成";
            }
            if (actions.contains("渲染")) {
                return "报告渲染";
            }
        }
        if (actions.isBlank()) {
            actions = switch (family) {
                case "DATA_QUERY", "QUERY_INTERPRETATION",
                        "METADATA_DISCOVERY" -> "查询与解释";
                case "FILE_PROCESSING" -> "解析与导出";
                case "CODE_EXECUTION" -> "执行与文件读写";
                case "TOOL_ORCHESTRATION" -> "调用与编排";
                case "REPORT_RENDERING" -> "渲染与生成";
                case "IMAGE_GENERATION" -> "图片生成";
                default -> "能力验证";
            };
        }
        if ("STATE_MUTATION".equals(family)
                && isActionLikeResource(resourceDomain)) {
            return resource + "操作";
        }
        return resource + actions;
    }

    private String queryCapabilityAction(String actions) {
        if (actions == null || actions.isBlank()) {
            return "查询";
        }
        if (actions.contains("生成 SQL") || actions.contains("筛选")
                || actions.contains("导出")) {
            return actions;
        }
        return "查询";
    }

    private String domainDescription(
            JsonNode candidate,
            String family,
            String resourceDomain,
            String boundaryGroup) {
        String entrypoints = readableList(entryMentions(candidate));
        String objects = readableList(objectMentions(candidate));
        String actions = readableActionList(actionMentions(candidate));
        String boundaries = readableList(boundaryMentions(candidate));
        String controls = readableList(firstArray(
                candidate.path("rawControlMentions"),
                candidate.path("raw_control_mentions"),
                candidate.path("controlBoundaries"),
                candidate.path("control_boundaries")));
        String sinks = readableList(sinkMentions(candidate));
        return "测试入口：" + valueOrDefault(entrypoints, "已发现入口")
                + "\n资源对象："
                + valueOrDefault(objects, resourceLabel(resourceDomain))
                + "\n测试动作："
                + valueOrDefault(actions, familyLabel(family))
                + "\n权限边界："
                + valueOrDefault(boundaries, boundaryLabel(boundaryGroup))
                + "\n控制边界："
                + valueOrDefault(controls, "未明确")
                + "\n输出/处理点："
                + valueOrDefault(sinks, "未明确");
    }

    private String capabilityDescription(JsonNode candidate) {
        String generated = firstNonBlank(
                candidate.path("displayDescription").asText(""),
                candidate.path("display_description").asText(""));
        if (isCleanDisplayDescription(generated)) {
            return generated.trim();
        }
        String objects = readableList(objectMentions(candidate));
        String actions = readableActionList(actionMentions(candidate));
        String entrypoints = readableList(entryMentions(candidate));
        String boundaries = readableList(boundaryMentions(candidate));
        String controls = readableList(controlMentions(candidate));
        List<String> clauses = new ArrayList<>();
        if (!entrypoints.isBlank()) {
            clauses.add("通过" + entrypoints);
        }
        if (!objects.isBlank() && !actions.isBlank()) {
            clauses.add("对" + objects + "执行" + actions);
        } else if (!objects.isBlank()) {
            clauses.add("处理" + objects);
        } else if (!actions.isBlank()) {
            clauses.add("支持" + actions);
        }
        if (!boundaries.isBlank()) {
            clauses.add("权限边界为" + boundaries);
        }
        if (!controls.isBlank()) {
            clauses.add("控制边界为" + controls);
        }
        return clauses.isEmpty()
                ? "已发现该业务能力，具体对象与入口尚未明确。"
                : String.join("，", clauses) + "。";
    }

    private boolean isCleanDisplayDescription(String value) {
        if (value == null || value.isBlank() || value.length() > 300) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return !normalized.contains("trace")
                && !normalized.contains("confidence")
                && !normalized.contains("rationale")
                && !normalized.contains("verificationstatus")
                && !normalized.contains("证据状态")
                && !normalized.contains("来源 trace")
                && !normalized.contains("漏洞")
                && !normalized.contains("vulnerab")
                && !normalized.contains("资源对象：")
                && !normalized.contains("测试动作：");
    }

    private String resourceScope(JsonNode candidate, String boundaryGroup) {
        String objects = readableList(objectMentions(candidate));
        String boundaries = readableList(boundaryMentions(candidate));
        String sinks = readableList(sinkMentions(candidate));
        return valueOrDefault(objects, "资源未明确") + "；权限边界："
                + valueOrDefault(boundaries, boundaryLabel(boundaryGroup))
                + "；输出/处理点：" + valueOrDefault(sinks, "未明确");
    }

    private String resourceLabel(String resourceDomain) {
        return switch (resourceDomain) {
            case "account", "account_id" -> "账户";
            case "transaction", "transactions", "transaction_record" -> "交易记录";
            case "fund", "funds", "money" -> "资金";
            case "order" -> "订单";
            case "aftersale" -> "售后";
            case "logistics" -> "物流";
            case "service", "services" -> "服务";
            case "dependency", "dependencies" -> "依赖";
            case "infrastructure", "topology" -> "基础设施";
            case "metrics", "metric" -> "指标";
            case "creator" -> "达人";
            case "ads" -> "广告";
            case "business_data" -> "业务数据";
            case "business_metric" -> "业务指标";
            case "metadata" -> "元数据与指标口径";
            case "file" -> "文件";
            case "sandbox_fs" -> "沙箱文件系统";
            case "report" -> "报告产物";
            case "output_formatting_layer" -> "输出格式层";
            case "business_tool" -> "业务工具";
            case "auth" -> "身份授权";
            case "session_state", "session_state_memory" -> "多轮会话状态";
            case "react_thought_action_loop", "react_loop", "react_state_machine",
                    "observation_trust" -> "ReAct Observation 信任边界";
            case "workspace", "user_data_workspace" -> "用户工作区";
            case "knowledge_base", "knowledge_base_path" -> "知识库路径";
            case "credential", "credential_context" -> "凭证上下文";
            case "merchant", "merchant_id" -> "商家标识";
            case "business_line" -> "业务线";
            case "time_range" -> "时间范围";
            case "dataset" -> "数据集";
            case "table", "sql_table" -> "数据表";
            case "image_generation", "visual_generation" -> "图片生成";
            case "todo_list", "task_list", "status_hint",
                    "system_notice" -> "系统状态提示";
            case "general" -> "通用";
            default -> resourceDomain.replace('_', ' ');
        };
    }

    private String titleResourceLabel(String resourceDomain, JsonNode candidate) {
        if (isActionLikeResource(resourceDomain)) {
            List<String> objects = textArray(objectMentions(candidate));
            if (!objects.isEmpty()) {
                return objects.get(0);
            }
        }
        return resourceLabel(resourceDomain);
    }

    private String familyLabel(String family) {
        return switch (family) {
            case "DATA_QUERY" -> "查询";
            case "QUERY_INTERPRETATION" -> "查询解释";
            case "METADATA_DISCOVERY" -> "元数据检索";
            case "FILE_PROCESSING" -> "文件处理";
            case "CODE_EXECUTION" -> "代码执行";
            case "TOOL_ORCHESTRATION" -> "工具编排";
            case "REPORT_RENDERING" -> "报告渲染";
            case "IMAGE_GENERATION" -> "图片生成";
            case "EXTERNAL_INTEGRATION" -> "外部集成";
            case "STATE_MUTATION" -> "状态变更";
            case "AUTH_WORKFLOW" -> "授权流程";
            default -> "应用操作";
        };
    }

    private boolean isNoiseCandidate(
            String family,
            String resourceDomain,
            JsonNode candidate) {
        if (Set.of(
                "system_context",
                "model_service_state",
                "runtime_context",
                "page_shell",
                "browser_context",
                "banner",
                "todo_list",
                "task_list",
                "status_hint",
                "system_notice").contains(resourceDomain)) {
            return true;
        }
        if (!"OTHER".equals(family)) {
            return false;
        }
        String content = (
                resourceDomain + " "
                        + readableList(objectMentions(candidate)) + " "
                        + readableList(entryMentions(candidate)) + " "
                        + candidate.path("rationale").asText(""))
                .toLowerCase(Locale.ROOT);
        return content.contains("system context")
                || content.contains("model service")
                || content.contains("todo list")
                || content.contains("task list")
                || content.contains("status hint")
                || content.contains("system notice")
                || content.contains("latest contents")
                || content.contains("系统上下文")
                || content.contains("模型服务状态")
                || content.contains("页面壳")
                || content.contains("待办列表")
                || content.contains("任务清单")
                || content.contains("状态提示");
    }

    private boolean isFormattingOnlyCandidate(
            String family,
            String resourceDomain,
            JsonNode candidate) {
        return isFormattingOnlySurface(
                family,
                resourceDomain + " "
                        + readableList(objectMentions(candidate)) + " "
                        + readableList(entryMentions(candidate)) + " "
                        + readableActionList(actionMentions(candidate)) + " "
                        + readableList(boundaryMentions(candidate)) + " "
                        + readableList(sinkMentions(candidate)) + " "
                        + candidate.path("rationale").asText(""),
                "",
                "");
    }

    private boolean isFormattingOnlySurface(
            String familyOrType,
            String scope,
            String description,
            String title) {
        String text = (familyOrType + " " + scope + " " + description + " "
                + title).toLowerCase(Locale.ROOT);
        boolean formattingOnly = text.contains("output_formatting_layer")
                || text.contains("markdown_like_formatting")
                || text.contains("structured_bullet_points")
                || text.contains("bullet")
                || text.contains("加粗语法")
                || text.contains("分点符号")
                || text.contains("输出格式");
        if (!formattingOnly) {
            return false;
        }
        boolean realArtifact = text.contains("csv")
                || text.contains("excel")
                || text.contains("xlsx")
                || text.contains("pdf")
                || text.contains("html")
                || text.contains("download")
                || text.contains("export")
                || text.contains("导出")
                || text.contains("下载")
                || text.contains("文件产物")
                || text.contains("报告文件");
        return !realArtifact;
    }

    private boolean isControlBoundaryCandidate(
            String family,
            String resourceDomain,
            JsonNode candidate) {
        String text = (family + " " + resourceDomain + " "
                + readableList(objectMentions(candidate)) + " "
                + readableList(boundaryMentions(candidate)) + " "
                + readableList(entryMentions(candidate)))
                .toLowerCase(Locale.ROOT);
        return isReactLoopResource(resourceDomain)
                || isSessionStateResource(resourceDomain)
                || text.contains("react_thought_action_loop")
                || text.contains("react state machine")
                || text.contains("observation:")
                || text.contains("session state")
                || text.contains("会话状态");
    }

    private boolean isReactLoopResource(String resourceDomain) {
        return "react_thought_action_loop".equals(resourceDomain)
                || "react_loop".equals(resourceDomain)
                || "react_state_machine".equals(resourceDomain)
                || "observation_trust".equals(resourceDomain);
    }

    private boolean isSessionStateResource(String resourceDomain) {
        return "session_state".equals(resourceDomain)
                || "session_state_memory".equals(resourceDomain);
    }

    private boolean isActionLikeResource(String resourceDomain) {
        Set<String> actionSlugs = Set.of(
                "query", "search", "lookup", "read", "write",
                "execute", "run", "transfer", "mutate", "update",
                "create", "delete", "export", "import", "parse",
                "extract", "render", "invoke", "call");
        String normalized = normalizeSlug(resourceDomain);
        if (actionSlugs.contains(normalized)) {
            return true;
        }
        for (String part : normalized.split("_")) {
            if (actionSlugs.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private String boundaryLabel(String boundaryGroup) {
        return switch (boundaryGroup) {
            case "tenant_scope" -> "租户边界";
            case "merchant_scope" -> "商家边界";
            case "owner_scope" -> "对象归属边界";
            case "filesystem_scope" -> "文件路径边界";
            case "permission_scope" -> "权限边界";
            case "tool_scope" -> "工具调用边界";
            default -> "声明边界";
        };
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

    private void applyEvidenceWithFallback(
            ObjectNode item,
            JsonNode source,
            List<String> validCapabilityKeys,
            Map<String, String> evidenceByCapability,
            List<String> issues) {
        if (!validCapabilityKeys.isEmpty()) {
            applyEvidence(item, validCapabilityKeys, evidenceByCapability,
                    issues);
            return;
        }
        String level = normalizeEvidenceLevel(firstNonBlank(
                source.path("evidenceLevel").asText(""),
                source.path("evidence_level").asText(""),
                source.path("evidenceSource").asText(""),
                source.path("evidence_source").asText(""),
                item.path("evidenceLevel").asText(""),
                item.path("evidence_level").asText(""),
                item.path("evidenceSource").asText(""),
                item.path("evidence_source").asText("")));
        switch (level) {
            case "RESULT_OBSERVED", "TOOL_OBSERVED", "OBSERVED" -> {
                item.put("evidenceSource", "OBSERVED");
                item.put("verificationStatus", "VERIFIED");
            }
            case "PARTIALLY_OBSERVED" -> {
                item.put("evidenceSource", "OBSERVED");
                item.put("verificationStatus", "PARTIALLY_VERIFIED");
            }
            case "EXTERNAL_EVIDENCE", "EXTERNAL_CLUE" -> {
                item.put("evidenceSource", "EXTERNAL_EVIDENCE");
                item.put("verificationStatus", "UNVERIFIED");
            }
            default -> {
                item.put("evidenceSource", "AGENT_CLAIM");
                item.put("verificationStatus", "UNVERIFIED");
            }
        }
    }

    private String normalizeEvidenceLevel(String value) {
        if (value == null || value.isBlank()) {
            return "CLAIMED";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("VERIFIED".equals(normalized)) {
            return "RESULT_OBSERVED";
        }
        if ("UNVERIFIED".equals(normalized)) {
            return "CLAIMED";
        }
        return normalized;
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
            String value = mentionText(item).trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private String firstMention(JsonNode node) {
        List<String> values = textArray(node);
        return values.isEmpty() ? "" : values.get(0);
    }

    private String mentionText(JsonNode item) {
        if (item == null || item.isNull()) {
            return "";
        }
        if (item.isObject()) {
            return firstNonBlank(
                    item.path("text").asText(""),
                    item.path("value").asText(""),
                    item.path("mention").asText(""),
                    item.path("name").asText(""));
        }
        return item.asText("");
    }

    private List<String> normalizeTextArray(JsonNode node) {
        return textArray(node).stream()
                .map(this::normalizeKey)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private ArrayNode normalizeArray(JsonNode node) {
        ArrayNode array = objectMapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isNumber()) {
                    String key = "n:" + item.asLong();
                    if (seen.add(key)) {
                        array.add(item.asLong());
                    }
                    continue;
                }
                String value = mentionText(item).trim();
                if (!value.isBlank() && seen.add("s:" + value)) {
                    array.add(value);
                }
            }
        }
        return array;
    }

    private JsonNode entryMentions(JsonNode candidate) {
        return firstArray(
                candidate.path("rawEntryMentions"),
                candidate.path("raw_entry_mentions"),
                candidate.path("entrypoints"),
                candidate.path("tools"));
    }

    private JsonNode objectMentions(JsonNode candidate) {
        return firstArray(
                candidate.path("rawObjectMentions"),
                candidate.path("raw_object_mentions"),
                candidate.path("resourceObjects"),
                candidate.path("resource_objects"));
    }

    private JsonNode actionMentions(JsonNode candidate) {
        return firstArray(
                candidate.path("rawActionMentions"),
                candidate.path("raw_action_mentions"),
                candidate.path("actions"),
                candidate.path("supportedActions"));
    }

    private JsonNode boundaryMentions(JsonNode candidate) {
        return firstArray(
                candidate.path("rawBoundaryMentions"),
                candidate.path("raw_boundary_mentions"),
                candidate.path("permissionBoundaries"),
                candidate.path("permission_boundaries"));
    }

    private JsonNode controlMentions(JsonNode candidate) {
        return firstArray(
                candidate.path("rawControlMentions"),
                candidate.path("raw_control_mentions"),
                candidate.path("controlBoundaries"),
                candidate.path("control_boundaries"));
    }

    private JsonNode sinkMentions(JsonNode candidate) {
        return firstArray(
                candidate.path("rawSinkMentions"),
                candidate.path("raw_sink_mentions"),
                candidate.path("sinks"),
                candidate.path("sink_types"));
    }

    private ObjectNode normalizedFeatures(
            JsonNode candidate,
            String family,
            String resourceDomain,
            String boundaryGroup) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("family", family);
        node.put("resourceDomain", resourceDomain);
        node.put("boundaryGroup", boundaryGroup);
        node.set("actionTypes", normalizeArray(actionMentions(candidate)));
        node.set("sinkTypes", normalizeArray(sinkMentions(candidate)));
        node.set("rawEntryMentions", normalizeArray(entryMentions(candidate)));
        node.set("rawObjectMentions", normalizeArray(objectMentions(candidate)));
        node.set("rawBoundaryMentions",
                normalizeArray(boundaryMentions(candidate)));
        node.set("controlBoundaries",
                normalizeArray(controlMentions(candidate)));
        return node;
    }

    private void preserveStructuredFields(ObjectNode item) {
        JsonNode features = item.path("normalizedFeatures");
        item.set("supportedActions", normalizeArray(firstArray(
                item.path("supportedActions"),
                item.path("actions"),
                features.path("actionTypes"))));
        item.set("resourceObjects", normalizeArray(firstArray(
                item.path("resourceObjects"),
                item.path("resource_objects"),
                features.path("rawObjectMentions"))));
        item.set("entrypoints", normalizeArray(firstArray(
                item.path("entrypoints"),
                features.path("rawEntryMentions"))));
        item.set("permissionBoundaries", normalizeArray(firstArray(
                item.path("permissionBoundaries"),
                item.path("permission_boundaries"),
                features.path("rawBoundaryMentions"))));
        item.set("controlBoundaries", normalizeArray(firstArray(
                item.path("controlBoundaries"),
                item.path("control_boundaries"),
                features.path("controlBoundaries"))));
        item.set("sourceTraceIds", normalizeArray(
                item.path("sourceTraceIds")));
        if (!item.hasNonNull("displayDescription")) {
            item.put("displayDescription",
                    item.path("description").asText(""));
        }
    }

    private JsonNode firstArray(JsonNode... nodes) {
        if (nodes == null) {
            return objectMapper.createArrayNode();
        }
        for (JsonNode node : nodes) {
            if (node != null && node.isArray() && !node.isEmpty()) {
                return node;
            }
        }
        return objectMapper.createArrayNode();
    }

    private String firstValue(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isArray() && !node.isEmpty()) {
                String value = mentionText(node.get(0)).trim();
                if (!value.isBlank()) {
                    return value;
                }
            }
            if (node != null && node.isTextual()) {
                String value = node.asText("").trim();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    private String relatedTool(JsonNode candidate) {
        String tool = firstValue(
                candidate.path("tools"),
                candidate.path("relatedTool"),
                candidate.path("related_tool"));
        if (!tool.isBlank()) {
            return tool;
        }
        for (JsonNode node : List.of(
                candidate.path("rawEntryMentions"),
                candidate.path("raw_entry_mentions"),
                candidate.path("entrypoints"))) {
            for (String value : textArray(node)) {
                String normalized = value.trim();
                if (normalized.matches(
                        "[A-Za-z][A-Za-z0-9_.:-]*[_:.-][A-Za-z0-9_.:-]+")) {
                    return normalized;
                }
            }
        }
        return "";
    }

    private String join(JsonNode node) {
        return String.join(" ", textArray(node));
    }

    private String readableList(JsonNode node) {
        return String.join("、", textArray(node));
    }

    private String readableActionList(JsonNode node) {
        return actionLabels(node).stream()
                .collect(java.util.stream.Collectors.joining("、"));
    }

    private List<String> actionLabels(JsonNode node) {
        List<String> labels = textArray(node).stream()
                .map(this::actionLabel)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (labels.size() <= 1) {
            return labels;
        }
        Set<String> specificActions = Set.of(
                "转账", "转移", "查询", "筛选", "导出", "解析", "读取",
                "写入", "上传", "下载", "列举", "查看", "分析",
                "可视化", "图片生成", "转换", "聚合", "渲染", "注入",
                "更新", "创建", "删除");
        boolean hasSpecific = labels.stream().anyMatch(specificActions::contains);
        if (!hasSpecific) {
            return labels;
        }
        return labels.stream()
                .filter(label -> !Set.of("执行", "调用", "处理", "操作", "变更")
                        .contains(label))
                .toList();
    }

    private String actionLabel(String action) {
        String normalized = action == null ? "" : action.trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.contains("转账")) {
            return "转账";
        }
        if (normalized.contains("transfer")) {
            return "转移";
        }
        if (normalized.contains("generate_image")
                || normalized.contains("image_generation")
                || normalized.contains("text-to-image")
                || normalized.contains("text_to_image")) {
            return "图片生成";
        }
        if (normalized.equals("generate_sql")
                || normalized.equals("sql_generation")) {
            return "生成 SQL";
        }
        if (normalized.contains("build")
                && (normalized.contains("chart")
                || normalized.contains("graph"))) {
            return "生成图表";
        }
        if (normalized.contains("chart.js")
                || normalized.contains("chartjs")
                || normalized.contains("chart config")
                || normalized.contains("chart configuration")) {
            return "生成图表";
        }
        if (normalized.contains("generate")
                && (normalized.contains("chart")
                || normalized.contains("report")
                || normalized.contains("visual"))) {
            return "生成图表";
        }
        if (normalized.startsWith("generate")) {
            return "生成";
        }
        if (normalized.contains("dark-mode")
                || normalized.contains("dark mode")
                || normalized.contains("styling")
                || normalized.contains("style")) {
            return "样式处理";
        }
        if (normalized.contains("combine")
                || normalized.contains("multi-dataset")
                || normalized.contains("multiple metrics")) {
            return "组合分析";
        }
        if (normalized.contains("compare")) {
            return "对比";
        }
        if (normalized.contains("answer")
                || normalized.contains("analytical question")) {
            return "查询";
        }
        if (normalized.contains("explain")
                || normalized.contains("trend")) {
            return "解释";
        }
        if (normalized.endsWith("s")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return switch (normalized) {
            case "query", "search", "lookup" -> "查询";
            case "export" -> "导出";
            case "explain", "interpret" -> "解释";
            case "parse", "extract" -> "解析";
            case "read", "retrieve", "load", "fetch" -> "读取";
            case "write", "save" -> "写入";
            case "upload" -> "上传";
            case "download" -> "下载";
            case "list" -> "列举";
            case "stat", "inspect" -> "查看";
            case "analyze" -> "分析";
            case "visualize", "chart" -> "可视化";
            case "convert", "transform" -> "转换";
            case "aggregate", "group", "summarize" -> "聚合";
            case "filter" -> "筛选";
            case "format" -> "格式化";
            case "execute", "run" -> "执行";
            case "chain", "compose" -> "串联";
            case "combine", "coordinate", "orchestrate" -> "编排";
            case "render" -> "渲染";
            case "invoke", "call" -> "调用";
            case "register" -> "注册";
            case "inject" -> "注入";
            case "accept", "trust" -> "接受";
            case "accumulate", "learn" -> "累积";
            case "mutate" -> "变更";
            case "update" -> "更新";
            case "create" -> "创建";
            case "delete", "remove", "unlink" -> "删除";
            default -> action == null ? "" : action;
        };
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
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
