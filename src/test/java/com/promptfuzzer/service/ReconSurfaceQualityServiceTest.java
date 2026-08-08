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

        assertEquals(2, result.applicationSurfaces().size());
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
}
