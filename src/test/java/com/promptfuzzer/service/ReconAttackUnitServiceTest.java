package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.dto.CreateReconAttackUnitRequest;
import com.promptfuzzer.dto.ReconAttackUnitResponse;
import com.promptfuzzer.entity.ReconApplicationSurface;
import com.promptfuzzer.entity.ReconAttackUnit;
import com.promptfuzzer.entity.ReconResult;
import com.promptfuzzer.repository.ReconApplicationSurfaceRepository;
import com.promptfuzzer.repository.ReconAttackUnitRepository;
import com.promptfuzzer.repository.ReconResultRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReconAttackUnitServiceTest {

    private final ReconAttackUnitRepository attackUnitRepository =
            mock(ReconAttackUnitRepository.class);
    private final ReconResultRepository resultRepository =
            mock(ReconResultRepository.class);
    private final ReconApplicationSurfaceRepository surfaceRepository =
            mock(ReconApplicationSurfaceRepository.class);
    private final ReconAttackCandidatePromptService promptService =
            mock(ReconAttackCandidatePromptService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReconAttackUnitService service = new ReconAttackUnitService(
            attackUnitRepository,
            resultRepository,
            surfaceRepository,
              mock(com.promptfuzzer.repository.ReconAttackCandidateRepository.class),
            promptService,
            objectMapper);

    @Test
    void aggregatesPrimaryAndSupportingCapabilitiesWithQualityGate()
            throws Exception {
        List<ReconApplicationSurface> surfaces = surfaces();
        when(resultRepository.findById(12L)).thenReturn(Optional.of(result()));
        when(surfaceRepository
                .findByReconResultIdAndDeletedFalseOrderByIdAsc(12L))
                .thenReturn(surfaces);
        when(promptService.synthesizeAttackUnits(any(), any(), anyInt()))
                .thenReturn(objectMapper.readTree("""
                        {
                          "attackUnits": [
                            {
                              "unitKey": "SANDBOX_CODE_EXECUTION",
                              "title": "沙箱代码执行边界",
                              "description": "测试代码与命令执行边界",
                              "primarySurfaceIds": [81, 82, 86],
                              "supportingSurfaceIds": [83, 84],
                              "negativeBoundarySurfaceIds": [86],
                              "resourceBoundary": "会话级 Linux 沙箱",
                              "securityControls": [
                                "COMMAND_EXECUTION",
                                "SANDBOX_ISOLATION"
                              ],
                              "riskDimensions": ["ARBITRARY_CODE_EXECUTION"],
                              "aggregationReason": "共享沙箱执行边界",
                              "riskLevel": "HIGH",
                              "confidence": "HIGH"
                            },
                            {
                              "unitKey": "FILE_INPUT",
                              "title": "文件输入与解析边界",
                              "primarySurfaceIds": [84],
                              "supportingSurfaceIds": [],
                              "negativeBoundarySurfaceIds": [],
                              "resourceBoundary": "/mnt/user-data/uploads",
                              "securityControls": ["FILE_ACCESS"],
                              "riskDimensions": ["UNTRUSTED_FILE_PARSING"],
                              "riskLevel": "MEDIUM",
                              "confidence": "LOW"
                            },
                            {
                              "unitKey": "UTILITY_ONLY",
                              "title": "辅助能力",
                              "primarySurfaceIds": [85],
                              "supportingSurfaceIds": [],
                              "negativeBoundarySurfaceIds": []
                            }
                          ]
                        }
                        """));
        when(attackUnitRepository.saveAll(any())).thenAnswer(invocation -> {
            List<ReconAttackUnit> units = invocation.getArgument(0);
            for (int i = 0; i < units.size(); i++) {
                units.get(i).setId(20L + i);
            }
            return units;
        });

        CreateReconAttackUnitRequest request =
                new CreateReconAttackUnitRequest();
        request.setSurfaceIds(List.of(50L));
        request.setMaxAttackUnits(6);

        List<ReconAttackUnitResponse> responses =
                service.generateAttackUnits(12L, request);

        assertEquals(2, responses.size());
        ReconAttackUnitResponse sandbox = responses.get(0);
        assertEquals(List.of(81L, 82L), sandbox.getPrimarySurfaceIds());
        assertEquals(List.of(83L), sandbox.getSupportingSurfaceIds());
        assertEquals(List.of(86L), sandbox.getNegativeBoundarySurfaceIds());
        assertEquals("HIGH", sandbox.getRiskLevel());
        assertEquals("MEDIUM", sandbox.getConfidence());

        ReconAttackUnitResponse file = responses.get(1);
        assertEquals(List.of(84L), file.getPrimarySurfaceIds());
        assertEquals(List.of(), file.getSupportingSurfaceIds());
    }

    @Test
    void rejectsSurfaceOutsideReconResult() {
        when(resultRepository.findById(12L)).thenReturn(Optional.of(result()));
        when(surfaceRepository
                .findByReconResultIdAndDeletedFalseOrderByIdAsc(12L))
                .thenReturn(surfaces());
        CreateReconAttackUnitRequest request =
                new CreateReconAttackUnitRequest();
        request.setSurfaceIds(List.of(404L));

        assertThrows(IllegalArgumentException.class,
                () -> service.generateAttackUnits(12L, request));
    }

    @Test
    void rejectsAggregationWithoutValidPrimaryCapability() throws Exception {
        when(resultRepository.findById(12L)).thenReturn(Optional.of(result()));
        when(surfaceRepository
                .findByReconResultIdAndDeletedFalseOrderByIdAsc(12L))
                .thenReturn(surfaces());
        when(promptService.synthesizeAttackUnits(any(), any(), anyInt()))
                .thenReturn(objectMapper.readTree("""
                        {
                          "attackUnits": [{
                            "unitKey": "INVALID",
                            "title": "无效聚合",
                            "primarySurfaceIds": [85, 86],
                            "supportingSurfaceIds": [83],
                            "negativeBoundarySurfaceIds": []
                          }]
                        }
                        """));

        assertThrows(IllegalStateException.class,
                () -> service.generateForBridge(12L, List.of(50L), 8));
    }

    @Test
    void reusesSelectedAttackUnitsForCandidateGeneration() {
        ReconAttackUnit unit = new ReconAttackUnit();
        unit.setId(20L);
        unit.setReconResultId(12L);
        when(resultRepository.findById(12L)).thenReturn(Optional.of(result()));
        when(attackUnitRepository.findAllById(Set.of(20L)))
                .thenReturn(List.of(unit));

        List<ReconAttackUnit> units =
                service.resolveForCandidateGeneration(
                        12L, List.of(20L), null, 8);

        assertEquals(List.of(unit), units);
    }

    @Test
    void listsOnlyLatestAttackUnitGeneration() {
        ReconAttackUnit oldUnit = new ReconAttackUnit();
        oldUnit.setId(20L);
        oldUnit.setReconResultId(12L);
        oldUnit.setGenerationId("old");
        oldUnit.setPrimarySurfaceIds("[81]");
        oldUnit.setSourceSurfaceSnapshot("[]");

        ReconAttackUnit latestUnit = new ReconAttackUnit();
        latestUnit.setId(21L);
        latestUnit.setReconResultId(12L);
        latestUnit.setGenerationId("latest");
        latestUnit.setPrimarySurfaceIds("[84]");
        latestUnit.setSourceSurfaceSnapshot("[]");

        when(resultRepository.findById(12L)).thenReturn(Optional.of(result()));
        when(attackUnitRepository.findByReconResultIdOrderByIdAsc(12L))
                .thenReturn(List.of(oldUnit, latestUnit));

        List<ReconAttackUnitResponse> responses =
                service.listAttackUnits(12L);

        assertEquals(1, responses.size());
        assertEquals(21L, responses.get(0).getId());
        assertEquals("latest", responses.get(0).getGenerationId());
    }

    private List<ReconApplicationSurface> surfaces() {
        ReconApplicationSurface domain = surface(
                50L, "SANDBOX", "沙箱与文件能力", "DOMAIN");
        domain.setSurfaceLevel(ReconApplicationSurface.SurfaceLevel.DOMAIN);
        domain.setSelectable(false);

        ReconApplicationSurface python = surface(
                81L, "CODE_EXECUTION.PYTHON", "Python脚本执行", "EXECUTION");
        python.setVerificationStatus(
                ReconApplicationSurface.VerificationStatus.PARTIALLY_VERIFIED);
        ReconApplicationSurface bash = surface(
                82L, "CODE_EXECUTION.BASH", "Bash命令执行", "EXECUTION");
        ReconApplicationSurface path = surface(
                83L, "SANDBOX.PATH", "沙箱路径结构", "ENVIRONMENT");
        ReconApplicationSurface csv = surface(
                84L, "FILE_IO.CSV", "CSV文件读取", "IO");
        ReconApplicationSurface utility = surface(
                85L, "UTILITY.MERMAID", "Mermaid图生成", "UTILITY");
        ReconApplicationSurface unsupported = surface(
                86L, "FILE_IO.LINEAGE", "字段级血缘检索（不支持）", "SEARCH");
        unsupported.setCurrentDescription("不支持返回字段级血缘");

        for (ReconApplicationSurface surface :
                List.of(python, bash, path, csv, utility, unsupported)) {
            surface.setParentSurfaceId(50L);
        }
        return List.of(domain, python, bash, path, csv, utility, unsupported);
    }

    private ReconApplicationSurface surface(
            Long id,
            String key,
            String title,
            String type) {
        ReconApplicationSurface surface = new ReconApplicationSurface();
        surface.setId(id);
        surface.setReconResultId(12L);
        surface.setCapabilityKey(key);
        surface.setCurrentTitle(title);
        surface.setOriginalTitle(title);
        surface.setCurrentDescription(title);
        surface.setOriginalDescription(title);
        surface.setSurfaceType(type);
        surface.setSurfaceLevel(
                ReconApplicationSurface.SurfaceLevel.CAPABILITY);
        surface.setSelectable(true);
        surface.setVerificationStatus(
                ReconApplicationSurface.VerificationStatus.UNVERIFIED);
        return surface;
    }

    private ReconResult result() {
        ReconResult result = new ReconResult();
        result.setId(12L);
        result.setReconTaskId(18L);
        result.setQualityStatus(ReconResult.QualityStatus.PARTIAL);
        result.setCurrentResult("{}");
        result.setCapabilityFacts("[]");
        return result;
    }
}
