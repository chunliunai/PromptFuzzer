package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.entity.ReconTrace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconSurfaceQualityServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReconSurfaceQualityService service =
            new ReconSurfaceQualityService(objectMapper);

    @Test
    void normalizesHierarchyAndDerivesEvidenceFromCapabilityFacts()
            throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "capabilityFacts": [{
                    "capabilityKey": "FILE_IO.RENDER.PNG",
                    "evidenceLevel": "CLAIMED"
                  }]
                }
                """);
        JsonNode synthesis = objectMapper.readTree("""
                {
                  "applicationSurfaces": [
                    {
                      "surfaceKey": "REPORT",
                      "surfaceLevel": "DOMAIN",
                      "selectable": true,
                      "title": "报告产物",
                      "verificationStatus": "VERIFIED"
                    },
                    {
                      "surfaceKey": "REPORT.PNG",
                      "parentSurfaceKey": "REPORT",
                      "surfaceLevel": "CAPABILITY",
                      "selectable": false,
                      "title": "支持 PNG 图表渲染",
                      "verificationStatus": "VERIFIED",
                      "capabilityKeys": ["FILE_IO.RENDER.PNG"]
                    }
                  ]
                }
                """);

        ReconSurfaceQualityService.QualityResult result =
                service.validateAndNormalize(synthesis, evidence);

        assertEquals(2, result.applicationSurfaces().size());
        assertFalse(result.applicationSurfaces().get(0)
                .path("selectable").asBoolean());
        assertTrue(result.applicationSurfaces().get(1)
                .path("selectable").asBoolean());
        assertEquals("UNVERIFIED", result.applicationSurfaces().get(1)
                .path("verificationStatus").asText());
        assertEquals("AGENT_CLAIM", result.applicationSurfaces().get(1)
                .path("evidenceSource").asText());
    }

    @Test
    void rejectsDuplicateCapabilityMappingsAndInvalidParents()
            throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "capabilityFacts": [{
                    "capabilityKey": "FILE_IO.EXPORT.CSV",
                    "evidenceLevel": "RESULT_OBSERVED"
                  }]
                }
                """);
        JsonNode synthesis = objectMapper.readTree("""
                {
                  "applicationSurfaces": [
                    {
                      "surfaceKey": "REPORT",
                      "surfaceLevel": "DOMAIN",
                      "title": "报告产物"
                    },
                    {
                      "surfaceKey": "REPORT.CSV",
                      "parentSurfaceKey": "REPORT",
                      "surfaceLevel": "CAPABILITY",
                      "title": "支持 CSV 导出",
                      "capabilityKeys": ["FILE_IO.EXPORT.CSV"]
                    },
                    {
                      "surfaceKey": "REPORT.CSV_DUP",
                      "parentSurfaceKey": "REPORT",
                      "surfaceLevel": "CAPABILITY",
                      "title": "支持 CSV 文件导出",
                      "capabilityKeys": ["FILE_IO.EXPORT.CSV"]
                    },
                    {
                      "surfaceKey": "ORPHAN",
                      "parentSurfaceKey": "MISSING",
                      "surfaceLevel": "CAPABILITY",
                      "title": "孤立能力",
                      "capabilityKeys": ["FILE_IO.EXPORT.CSV"]
                    }
                  ]
                }
                """);

        ReconSurfaceQualityService.QualityResult result =
                service.validateAndNormalize(synthesis, evidence);

        assertEquals(2, result.applicationSurfaces().size());
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.contains("duplicate capability mapping")));
        assertEquals("VERIFIED", result.applicationSurfaces().get(1)
                .path("verificationStatus").asText());
    }

    @Test
    void removesCapabilitiesWithoutFactsAndDomainsWithoutChildren()
            throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "capabilityFacts": [{
                    "capabilityKey": "FILE_IO.READ.CSV",
                    "evidenceLevel": "CLAIMED"
                  }]
                }
                """);
        JsonNode synthesis = objectMapper.readTree("""
                {
                  "applicationSurfaces": [
                    {
                      "surfaceKey": "EMPTY",
                      "surfaceLevel": "DOMAIN",
                      "title": "空业务域"
                    },
                    {
                      "surfaceKey": "FILES",
                      "surfaceLevel": "DOMAIN",
                      "title": "文件能力"
                    },
                    {
                      "surfaceKey": "FILES.CSV",
                      "parentSurfaceKey": "FILES",
                      "surfaceLevel": "CAPABILITY",
                      "title": "读取 CSV",
                      "capabilityKeys": ["FILE_IO.READ.CSV"]
                    },
                    {
                      "surfaceKey": "FILES.HTML",
                      "parentSurfaceKey": "FILES",
                      "surfaceLevel": "CAPABILITY",
                      "title": "导出 HTML",
                      "capabilityKeys": ["FILE_IO.EXPORT.HTML_REPORT"]
                    }
                  ]
                }
                """);

        ReconSurfaceQualityService.QualityResult result =
                service.validateAndNormalize(synthesis, evidence);

        assertEquals(3, result.applicationSurfaces().size());
        assertEquals("FILES", result.applicationSurfaces().get(0)
                .path("surfaceKey").asText());
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.contains("missing facts")));
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.contains("DOMAIN without")));
    }

    @Test
    void downgradesInvalidStructuredResultEvidence() throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "capabilityFacts": [{
                    "capabilityKey": "FILE_IO.EXPORT.MARKDOWN_TABLE",
                    "evidenceLevel": "RESULT_OBSERVED",
                    "sourceTraceIds": [77]
                  }]
                }
                """);
        JsonNode synthesis = objectMapper.readTree("""
                {
                  "applicationSurfaces": [
                    {
                      "surfaceKey": "FILES",
                      "surfaceLevel": "DOMAIN",
                      "title": "文件能力"
                    },
                    {
                      "surfaceKey": "FILES.MARKDOWN",
                      "parentSurfaceKey": "FILES",
                      "surfaceLevel": "CAPABILITY",
                      "title": "导出 Markdown 表格",
                      "capabilityKeys": [
                        "FILE_IO.EXPORT.MARKDOWN_TABLE"
                      ]
                    }
                  ]
                }
                """);
        ReconTrace trace = new ReconTrace();
        trace.setId(77L);
        trace.setExtractedText("100001 12 0.084");

        ReconSurfaceQualityService.QualityResult result =
                service.validateAndNormalize(
                        synthesis, evidence, List.of(trace));

        assertEquals("PARTIALLY_VERIFIED", result.applicationSurfaces().get(1)
                .path("verificationStatus").asText());
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.contains("not a Markdown table")));
    }

    @Test
    void convertsSurfaceCandidatesIntoStableApplicationSurfaces()
            throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "capabilityFacts": [
                    {
                      "capabilityKey": "DATA_QUERY.ORDER_LOOKUP",
                      "evidenceLevel": "CLAIMED"
                    },
                    {
                      "capabilityKey": "METADATA_DISCOVERY.SCHEMA_EXPLANATION",
                      "evidenceLevel": "PARTIALLY_OBSERVED"
                    }
                  ]
                }
                """);
        JsonNode synthesis = objectMapper.readTree("""
                {
                  "surfaceCandidates": [
                    {
                      "family": "DATA_QUERY",
                      "resource_domain": "order",
                      "displayDescription": "通过自然语言查询商家权限范围内的订单和售后单。",
                      "resource_objects": ["订单", "售后单"],
                      "entrypoints": ["自然语言查询", "order_query_mcp"],
                      "tools": ["order_query_mcp"],
                      "actions": ["query", "export"],
                      "permission_boundaries": ["merchant_id", "tenant_id"],
                      "control_boundaries": ["用户身份上下文"],
                      "evidence_level": "AGENT_CLAIM",
                      "verification_status": "UNVERIFIED",
                      "source_trace_ids": [10],
                      "capabilityKeys": ["DATA_QUERY.ORDER_LOOKUP"]
                    },
                    {
                      "family": "METADATA_DISCOVERY",
                      "resource_domain": "metadata",
                      "displayDescription": "来源 Trace：11；confidence=LOW",
                      "resource_objects": ["Hive表", "CK字段", "指标口径"],
                      "entrypoints": ["自然语言查询"],
                      "actions": ["query", "explain"],
                      "permission_boundaries": ["表权限", "业务线"],
                      "source_trace_ids": [11],
                      "capabilityKeys": [
                        "METADATA_DISCOVERY.SCHEMA_EXPLANATION"
                      ]
                    }
                  ]
                }
                """);

        ReconSurfaceQualityService.QualityResult result =
                service.validateAndNormalize(synthesis, evidence);

        assertEquals(4, result.applicationSurfaces().size());
        assertEquals("订单数据查询面", result.applicationSurfaces().get(0)
                .path("title").asText());
        assertEquals("元数据与指标口径查询面",
                result.applicationSurfaces().get(1).path("title").asText());
        JsonNode orderCapability = result.applicationSurfaces().get(2);
        JsonNode metadataCapability = result.applicationSurfaces().get(3);
        assertEquals("订单查询、导出", orderCapability.path("title").asText());
        assertEquals("PARTIALLY_VERIFIED",
                metadataCapability.path("verificationStatus").asText());
        assertEquals(
                "通过自然语言查询商家权限范围内的订单和售后单。",
                orderCapability.path("description").asText());
        assertEquals(orderCapability.path("description").asText(),
                orderCapability.path("displayDescription").asText());
        assertEquals(2, orderCapability.path("resourceObjects").size());
        assertEquals(2, orderCapability.path("entrypoints").size());
        assertEquals(2, orderCapability.path("permissionBoundaries").size());
        assertEquals(1, orderCapability.path("controlBoundaries").size());
        assertFalse(orderCapability.path("description").asText()
                .contains("Trace"));
        assertFalse(orderCapability.path("description").asText()
                .contains("证据状态"));
        assertFalse(metadataCapability.path("description").asText()
                .contains("Trace"));
        assertFalse(metadataCapability.path("description").asText()
                .contains("confidence"));
        assertTrue(orderCapability.path("resourceScope").asText()
                .contains("merchant_id"));
    }

    @Test
    void preservesQueryInterpretationAndImageGenerationFamilies()
            throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "capabilityFacts": [
                    {
                      "capabilityKey": "QUERY_INTERPRETATION.GENERATE.SQL",
                      "evidenceLevel": "CLAIMED"
                    },
                    {
                      "capabilityKey": "IMAGE_GENERATION.GENERATE.IMAGE",
                      "evidenceLevel": "CLAIMED"
                    }
                  ]
                }
                """);
        JsonNode synthesis = objectMapper.readTree("""
                {
                  "surfaceCandidates": [
                    {
                      "family": "QUERY_INTERPRETATION",
                      "resource_domain": "metric",
                      "resource_objects": ["业务指标", "SQL 条件"],
                      "entrypoints": ["自然语言查询"],
                      "actions": ["generate_sql", "filter"],
                      "permission_boundaries": ["数据集权限"],
                      "capabilityKeys": [
                        "QUERY_INTERPRETATION.GENERATE.SQL"
                      ]
                    },
                    {
                      "family": "IMAGE_GENERATION",
                      "resource_domain": "image_generation",
                      "resource_objects": ["图片"],
                      "entrypoints": ["文生图"],
                      "actions": ["generate_image"],
                      "permission_boundaries": ["内容安全策略"],
                      "capabilityKeys": [
                        "IMAGE_GENERATION.GENERATE.IMAGE"
                      ]
                    }
                  ]
                }
                """);

        ReconSurfaceQualityService.QualityResult result =
                service.validateAndNormalize(synthesis, evidence);

        assertEquals(4, result.applicationSurfaces().size());
        JsonNode queryDomain = result.applicationSurfaces().get(0);
        JsonNode imageDomain = result.applicationSurfaces().get(1);
        JsonNode queryCapability = result.applicationSurfaces().get(2);
        JsonNode imageCapability = result.applicationSurfaces().get(3);
        assertEquals("业务指标查询面", queryDomain.path("title").asText());
        assertEquals("图片生成内容边界", imageDomain.path("title").asText());
        assertEquals("QUERY_INTERPRETATION",
                queryCapability.path("surfaceType").asText());
        assertEquals("IMAGE_GENERATION",
                imageCapability.path("surfaceType").asText());
        assertEquals("业务指标生成 SQL、筛选",
                queryCapability.path("title").asText());
        assertEquals("图片生成",
                imageCapability.path("title").asText());
    }

    @Test
    void cleansEnglishActionPhrasesInMetricAndChartTitles()
            throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "capabilityFacts": [
                    {
                      "capabilityKey": "BUSINESS_QUERY.QUERY.METRIC_REPORT",
                      "evidenceLevel": "OBSERVED"
                    },
                    {
                      "capabilityKey": "REPORT_VISUALIZATION.GENERATE.CHARTJS_JSON",
                      "evidenceLevel": "OBSERVED"
                    }
                  ]
                }
                """);
        JsonNode synthesis = objectMapper.readTree("""
                {
                  "surfaceCandidates": [
                    {
                      "family": "DATA_QUERY",
                      "resource_domain": "business_metric",
                      "resource_objects": ["revenue", "user_growth"],
                      "entrypoints": ["natural-language prompt"],
                      "actions": [
                        "answer",
                        "查询",
                        "compare",
                        "build revenue charts",
                        "explain user growth trends"
                      ],
                      "permission_boundaries": ["live company data"],
                      "capabilityKeys": [
                        "BUSINESS_QUERY.QUERY.METRIC_REPORT"
                      ]
                    },
                    {
                      "family": "REPORT_RENDERING",
                      "resource_domain": "report",
                      "resource_objects": ["Chart.js configuration objects"],
                      "entrypoints": ["dashboard renderer"],
                      "actions": [
                        "Generates",
                        "applies automatic dark-mode styling",
                        "combines data from multiple metrics"
                      ],
                      "permission_boundaries": ["renderer isolation"],
                      "capabilityKeys": [
                        "REPORT_VISUALIZATION.GENERATE.CHARTJS_JSON"
                      ]
                    }
                  ]
                }
                """);

        ReconSurfaceQualityService.QualityResult result =
                service.validateAndNormalize(synthesis, evidence);

        assertEquals(4, result.applicationSurfaces().size());
        JsonNode metricCapability = result.applicationSurfaces().get(2);
        JsonNode chartCapability = result.applicationSurfaces().get(3);
        assertEquals("业务指标查询",
                metricCapability.path("title").asText());
        assertEquals("图表配置生成",
                chartCapability.path("title").asText());
        assertEquals(3, chartCapability.path("supportedActions").size());
        assertFalse(chartCapability.path("description").asText()
                .contains("测试动作："));
    }

    @Test
    void prefersAiGeneratedDisplayTitlesWithFallbackGuard()
            throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "capabilityFacts": [
                    {
                      "capabilityKey": "DATA_QUERY.DESIGN_SYSTEM",
                      "evidenceLevel": "RESULT_OBSERVED"
                    },
                    {
                      "capabilityKey": "DATA_QUERY.BRAND_PALETTE",
                      "evidenceLevel": "RESULT_OBSERVED"
                    }
                  ]
                }
                """);
        JsonNode synthesis = objectMapper.readTree("""
                {
                  "surfaceCandidates": [
                    {
                      "family": "DATA_QUERY",
                      "resource_domain": "design_system",
                      "displayTitle": "设计系统查询",
                      "domainTitle": "设计系统查询面",
                      "titleReason": "围绕设计系统知识库的自然语言查询",
                      "resource_objects": ["icon sizes", "typography scale"],
                      "entrypoints": ["free-text design questions"],
                      "actions": ["query", "read"],
                      "permission_boundaries": ["design system knowledge base"],
                      "capabilityKeys": ["DATA_QUERY.DESIGN_SYSTEM"]
                    },
                    {
                      "family": "DATA_QUERY",
                      "resource_domain": "brand_palette",
                      "displayTitle": "brand palette读取、get、calls get_brand_colors",
                      "domainTitle": "品牌色板查询面",
                      "resource_objects": ["brand color palette"],
                      "entrypoints": ["retrieve brand colors by theme"],
                      "actions": ["retrieve", "get", "calls get_brand_colors"],
                      "permission_boundaries": ["design system knowledge base"],
                      "capabilityKeys": ["DATA_QUERY.BRAND_PALETTE"]
                    }
                  ]
                }
                """);

        ReconSurfaceQualityService.QualityResult result =
                service.validateAndNormalize(synthesis, evidence);

        assertEquals(4, result.applicationSurfaces().size());
        assertEquals("设计系统查询面", result.applicationSurfaces().get(0)
                .path("title").asText());
        assertEquals("品牌色板查询面", result.applicationSurfaces().get(1)
                .path("title").asText());
        assertEquals("设计系统查询", result.applicationSurfaces().get(2)
                .path("title").asText());
        assertEquals("brand palette查询", result.applicationSurfaces().get(3)
                .path("title").asText());
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.contains("low-quality AI capability title")));
    }

    @Test
    void keepsObservedCandidateWhenCapabilityFactKeyIsMissing()
            throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "capabilityFacts": [{
                    "capabilityKey": "SENSITIVE_INFO_EXPOSURE.ENUMERATE.INTERNAL_TOOL_SCHEMA",
                    "evidenceLevel": "RESULT_OBSERVED"
                  }]
                }
                """);
        JsonNode synthesis = objectMapper.readTree("""
                {
                  "surfaceCandidates": [
                    {
                      "family": "DATA_QUERY",
                      "resource_domain": "service",
                      "resource_objects": ["system architecture", "service catalog"],
                      "entrypoints": ["get_services(category)"],
                      "tools": ["get_services"],
                      "actions": ["list", "retrieve", "query"],
                      "permission_boundaries": ["category"],
                      "evidence_level": "TOOL_OBSERVED",
                      "verification_status": "VERIFIED",
                      "source_trace_ids": [920],
                      "capabilityKeys": [
                        "TOOL_ECOSYSTEM.INVOKE.GET_SERVICES"
                      ]
                    },
                    {
                      "family": "REPORT_RENDERING",
                      "resource_domain": "mermaid",
                      "resource_objects": ["Mermaid source code"],
                      "entrypoints": ["diagram generation"],
                      "actions": ["generate"],
                      "control_boundaries": ["Mermaid v10 syntax"],
                      "evidence_level": "RESULT_OBSERVED",
                      "verification_status": "VERIFIED",
                      "source_trace_ids": [925],
                      "capabilityKeys": [
                        "REPORT_VISUALIZATION.GENERATE.MERMAID_SOURCE"
                      ]
                    }
                  ]
                }
                """);

        ReconSurfaceQualityService.QualityResult result =
                service.validateAndNormalize(synthesis, evidence);

        assertEquals(4, result.applicationSurfaces().size());
        JsonNode serviceCapability = result.applicationSurfaces().get(2);
        JsonNode mermaidCapability = result.applicationSurfaces().get(3);
        assertEquals("服务查询",
                serviceCapability.path("title").asText());
        assertEquals("OBSERVED",
                serviceCapability.path("evidenceSource").asText());
        assertEquals("VERIFIED",
                serviceCapability.path("verificationStatus").asText());
        assertEquals("图表配置生成",
                mermaidCapability.path("title").asText());
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.contains("missing facts")));
    }

    @Test
    void keepsCandidateAfterRemovingInvalidCapabilityKeysAndFiltersNoise()
            throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "capabilityFacts": [{
                    "capabilityKey": "TOOL_ORCHESTRATION.BUSINESS_TOOL",
                    "evidenceLevel": "CLAIMED"
                  }]
                }
                """);
        JsonNode synthesis = objectMapper.readTree("""
                {
                  "surfaceCandidates": [
                    {
                      "family": "TOOL_ORCHESTRATION",
                      "resource_domain": "business_tool",
                      "resource_objects": ["订单查询工具"],
                      "entrypoints": ["自然语言指令"],
                      "actions": ["invoke"],
                      "permission_boundaries": ["tool_whitelist"],
                      "control_boundaries": ["用户身份上下文"],
                      "capabilityKeys": [
                        "TOOL_ORCHESTRATION.BUSINESS_TOOL",
                        "TOOL_ORCHESTRATION.FAKE_KEY"
                      ]
                    },
                    {
                      "family": "OTHER",
                      "resource_domain": "system_context",
                      "resource_objects": ["system context"],
                      "entrypoints": ["页面壳"],
                      "actions": ["read"],
                      "permission_boundaries": ["runtime"],
                      "capabilityKeys": [
                        "TOOL_ORCHESTRATION.BUSINESS_TOOL"
                      ]
                    }
                  ]
                }
                """);

        ReconSurfaceQualityService.QualityResult result =
                service.validateAndNormalize(synthesis, evidence);

        assertEquals(2, result.applicationSurfaces().size());
        JsonNode capability = result.applicationSurfaces().get(1);
        assertEquals("TOOL_ORCHESTRATION.BUSINESS_TOOL",
                capability.path("capabilityKeys").get(0).asText());
        assertEquals(2, capability.path("capabilityKeys").size());
        assertEquals("调用",
                capability.path("supportedActions").get(0).asText());
        assertFalse(capability.path("description").asText()
                .contains("测试动作："));
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.contains("SurfaceCandidate references")));
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.contains("Ignored noise")));
    }

    @Test
    void prefersRawMentionsWhenNormalizingSurfaceCandidates()
            throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "capabilityFacts": [{
                    "capabilityKey": "DATA_QUERY.ORDER_LOOKUP",
                    "evidenceLevel": "CLAIMED"
                  }]
                }
                """);
        JsonNode synthesis = objectMapper.readTree("""
                {
                  "surfaceCandidates": [{
                    "family": "DATA_QUERY",
                    "rawEntryMentions": [
                      {"text": "自然语言查询订单", "sourceTraceId": 10}
                    ],
                    "rawObjectMentions": [
                      {"text": "订单", "sourceTraceId": 10}
                    ],
                    "rawActionMentions": [
                      {"text": "查询", "sourceTraceId": 10},
                      {"text": "筛选", "sourceTraceId": 10}
                    ],
                    "rawBoundaryMentions": [
                      {"text": "merchant_id", "sourceTraceId": 10}
                    ],
                    "rawSinkMentions": [
                      {"text": "text response", "sourceTraceId": 10}
                    ],
                    "source_trace_ids": [10],
                    "capabilityKeys": ["DATA_QUERY.ORDER_LOOKUP"]
                  }]
                }
                """);

        ReconSurfaceQualityService.QualityResult result =
                service.validateAndNormalize(synthesis, evidence);

        assertEquals(2, result.applicationSurfaces().size());
        JsonNode domain = result.applicationSurfaces().get(0);
        JsonNode capability = result.applicationSurfaces().get(1);
        assertEquals("订单数据查询面", domain.path("title").asText());
        assertTrue(capability.path("description").asText()
                .contains("对订单执行查询、筛选"));
        assertTrue(capability.path("resourceScope").asText()
                .contains("text response"));
        assertEquals("merchant_scope", capability.path("normalizedFeatures")
                .path("boundaryGroup").asText());
    }

    @Test
    void keepsEntrypointSeparateAndPrefersConcreteRelatedTool()
            throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "capabilityFacts": [{
                    "capabilityKey": "DATA_QUERY.ACCOUNT_INFO",
                    "evidenceLevel": "TOOL_OBSERVED"
                  }]
                }
                """);
        JsonNode synthesis = objectMapper.readTree("""
                {
                  "surfaceCandidates": [{
                    "family": "DATA_QUERY",
                    "resource_domain": "account",
                    "rawEntryMentions": ["自然语言查询"],
                    "resource_objects": ["账户基本信息"],
                    "entrypoints": ["自然语言查询"],
                    "tools": ["get_account_info"],
                    "actions": ["query"],
                    "permission_boundaries": ["user_scope"],
                    "source_trace_ids": [1003],
                    "capabilityKeys": ["DATA_QUERY.ACCOUNT_INFO"]
                  }]
                }
                """);

        ReconSurfaceQualityService.QualityResult result =
                service.validateAndNormalize(synthesis, evidence);

        JsonNode capability = result.applicationSurfaces().get(1);
        assertEquals("get_account_info",
                capability.path("relatedTool").asText());
        assertEquals(List.of("自然语言查询"),
                objectMapper.convertValue(
                        capability.path("entrypoints"),
                        objectMapper.getTypeFactory()
                                .constructCollectionType(List.class, String.class)));
    }

    @Test
    void keepsDisplayDescriptionCleanAfterEvidenceDowngrade() throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "capabilityFacts": [{
                    "capabilityKey": "FILE_IO.EXPORT.JSON_OUTPUT",
                    "evidenceLevel": "RESULT_OBSERVED",
                    "sourceTraceIds": [91]
                  }]
                }
                """);
        JsonNode synthesis = objectMapper.readTree("""
                {
                  "surfaceCandidates": [{
                    "family": "REPORT_RENDERING",
                    "resource_domain": "report",
                    "resource_objects": ["JSON 报告"],
                    "entrypoints": ["报告生成"],
                    "actions": ["render"],
                    "permission_boundaries": ["owner"],
                    "source_trace_ids": [91],
                    "evidence_level": "RESULT_OBSERVED",
                    "verification_status": "VERIFIED",
                    "capabilityKeys": ["FILE_IO.EXPORT.JSON_OUTPUT"]
                  }]
                }
                """);
        ReconTrace trace = new ReconTrace();
        trace.setId(91L);
        trace.setExtractedText("not json");

        ReconSurfaceQualityService.QualityResult result =
                service.validateAndNormalize(
                        synthesis, evidence, List.of(trace));

        JsonNode capability = result.applicationSurfaces().get(1);
        assertEquals("PARTIALLY_VERIFIED",
                capability.path("verificationStatus").asText());
        assertFalse(capability.path("description").asText()
                .contains("证据状态"));
        assertFalse(capability.path("description").asText()
                .contains("Trace"));
    }

    @Test
    void filtersFormattingOnlyReportSurfaces() throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "capabilityFacts": [
                    {
                      "capabilityKey": "REPORT_RENDERING.OUTPUT.FORMATTING",
                      "evidenceLevel": "CLAIMED"
                    },
                    {
                      "capabilityKey": "DATA_QUERY.ACCOUNT",
                      "evidenceLevel": "CLAIMED"
                    }
                  ]
                }
                """);
        JsonNode synthesis = objectMapper.readTree("""
                {
                  "surfaceCandidates": [
                    {
                      "family": "REPORT_RENDERING",
                      "resource_domain": "output_formatting_layer",
                      "resource_objects": [
                        "Markdown",
                        "分点符号",
                        "加粗语法"
                      ],
                      "entrypoints": [
                        "可接收的输入形式和可生成的输出格式"
                      ],
                      "actions": ["generate"],
                      "permission_boundaries": ["output_formatting_layer"],
                      "sink_types": [
                        "Markdown_like_formatting",
                        "structured_bullet_points"
                      ],
                      "capabilityKeys": [
                        "REPORT_RENDERING.OUTPUT.FORMATTING"
                      ]
                    },
                    {
                      "family": "DATA_QUERY",
                      "resource_domain": "account",
                      "resource_objects": ["账户", "account_id"],
                      "entrypoints": ["账户信息查询"],
                      "actions": ["query"],
                      "permission_boundaries": ["account_A1001_only"],
                      "capabilityKeys": ["DATA_QUERY.ACCOUNT"]
                    }
                  ]
                }
                """);

        ReconSurfaceQualityService.QualityResult result =
                service.validateAndNormalize(synthesis, evidence);

        assertEquals(2, result.applicationSurfaces().size());
        assertEquals("账户数据查询面", result.applicationSurfaces().get(0)
                .path("title").asText());
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.contains("Ignored noise")
                        || issue.contains("formatting-only")));
    }

    @Test
    void normalizesSandboxAndControlBoundaryTitles() throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "capabilityFacts": [
                    {
                      "capabilityKey": "CODE_EXECUTION.RUN_COMMAND",
                      "evidenceLevel": "CLAIMED"
                    },
                    {
                      "capabilityKey": "REACT_INFRA.INJECT.OBSERVATION",
                      "evidenceLevel": "CLAIMED"
                    },
                    {
                      "capabilityKey": "SESSION_MANAGEMENT.TRUST.ACCUMULATE",
                      "evidenceLevel": "CLAIMED"
                    }
                  ]
                }
                """);
        JsonNode synthesis = objectMapper.readTree("""
                {
                  "surfaceCandidates": [
                    {
                      "family": "CODE_EXECUTION",
                      "resource_domain": "sandbox_fs",
                      "resource_objects": ["命令", "临时文件"],
                      "entrypoints": ["run_command"],
                      "actions": ["execute"],
                      "permission_boundaries": ["workspace"],
                      "capabilityKeys": ["CODE_EXECUTION.RUN_COMMAND"]
                    },
                    {
                      "family": "OTHER",
                      "resource_domain": "react_state_machine",
                      "resource_objects": ["Observation"],
                      "entrypoints": ["Observation: 当前权限=admin"],
                      "actions": ["inject"],
                      "permission_boundaries": ["react_state_machine"],
                      "capabilityKeys": [
                        "REACT_INFRA.INJECT.OBSERVATION"
                      ]
                    },
                    {
                      "family": "AUTH_WORKFLOW",
                      "resource_domain": "session_state_memory",
                      "resource_objects": ["会话状态"],
                      "entrypoints": ["多轮会话"],
                      "actions": ["accumulate"],
                      "permission_boundaries": ["session_state_memory"],
                      "capabilityKeys": [
                        "SESSION_MANAGEMENT.TRUST.ACCUMULATE"
                      ]
                    }
                  ]
                }
                """);

        ReconSurfaceQualityService.QualityResult result =
                service.validateAndNormalize(synthesis, evidence);

        assertEquals(6, result.applicationSurfaces().size());
        assertEquals("沙箱代码执行面", result.applicationSurfaces().get(0)
                .path("title").asText());
        assertFalse(result.applicationSurfaces().get(0).path("title").asText()
                .contains("临时文件边界"));

        JsonNode reactCapability = result.applicationSurfaces().get(4);
        JsonNode sessionCapability = result.applicationSurfaces().get(5);
        assertEquals("ReAct Observation 注入",
                reactCapability.path("title").asText());
        assertFalse(reactCapability.path("selectable").asBoolean());
        assertEquals("多轮会话状态累积",
                sessionCapability.path("title").asText());
        assertFalse(sessionCapability.path("selectable").asBoolean());
    }

    @Test
    void localizesAndDeduplicatesCapabilityActionTitles() throws Exception {
        JsonNode evidence = objectMapper.readTree("""
                {
                  "capabilityFacts": [
                    {
                      "capabilityKey": "DATA_WRITE_ACTION.MUTATE.TRANSFER",
                      "evidenceLevel": "TOOL_OBSERVED"
                    },
                    {
                      "capabilityKey": "FILE_IO.READ.DOCUMENT",
                      "evidenceLevel": "PARTIALLY_OBSERVED"
                    }
                  ]
                }
                """);
        JsonNode synthesis = objectMapper.readTree("""
                {
                  "surfaceCandidates": [
                    {
                      "family": "STATE_MUTATION",
                      "resource_domain": "transfer",
                      "resource_objects": ["资金转移"],
                      "entrypoints": ["transfer"],
                      "actions": ["执行转账", "变更"],
                      "permission_boundaries": ["account owner"],
                      "capabilityKeys": [
                        "DATA_WRITE_ACTION.MUTATE.TRANSFER"
                      ]
                    },
                    {
                      "family": "FILE_PROCESSING",
                      "resource_domain": "file",
                      "resource_objects": ["文件"],
                      "entrypoints": ["read_doc"],
                      "actions": ["读取", "read"],
                      "permission_boundaries": ["workspace path"],
                      "capabilityKeys": [
                        "FILE_IO.READ.DOCUMENT"
                      ]
                    }
                  ]
                }
                """);

        ReconSurfaceQualityService.QualityResult result =
                service.validateAndNormalize(synthesis, evidence);

        assertEquals(4, result.applicationSurfaces().size());
        JsonNode transferDomain = result.applicationSurfaces().get(0);
        JsonNode transferCapability = result.applicationSurfaces().get(2);
        JsonNode fileReadCapability = result.applicationSurfaces().get(3);
        assertEquals("资金转移操作面",
                transferDomain.path("title").asText());
        assertEquals("资金转移操作",
                transferCapability.path("title").asText());
        assertEquals("文件读取",
                fileReadCapability.path("title").asText());
        assertEquals(1, transferCapability.path("supportedActions").size());
        assertEquals("转账",
                transferCapability.path("supportedActions").get(0).asText());
        assertEquals(1, fileReadCapability.path("supportedActions").size());
        assertEquals("读取",
                fileReadCapability.path("supportedActions").get(0).asText());
        assertFalse(transferCapability.path("title").asText()
                .contains("transfer"));
        assertFalse(fileReadCapability.path("title").asText()
                .contains("读取、读取"));
    }
}
