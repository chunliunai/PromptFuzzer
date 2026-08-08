package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.dto.CreateReconAttackCandidateRequest;
import com.promptfuzzer.dto.ReconAttackCandidateResponse;
import com.promptfuzzer.dto.TaskResponse;
import com.promptfuzzer.dto.UpdateReconAttackCandidateRequest;
import com.promptfuzzer.entity.ReconAttackCandidate;
import com.promptfuzzer.entity.ReconAttackUnit;
import com.promptfuzzer.entity.ReconResult;
import com.promptfuzzer.entity.ReconTask;
import com.promptfuzzer.repository.ReconAttackCandidateRepository;
import com.promptfuzzer.repository.ReconResultRepository;
import com.promptfuzzer.repository.ReconTaskRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconAttackCandidateServiceTest {

    private final ReconAttackCandidateRepository candidateRepository =
            mock(ReconAttackCandidateRepository.class);
    private final ReconResultRepository resultRepository =
            mock(ReconResultRepository.class);
    private final ReconTaskRepository reconTaskRepository =
            mock(ReconTaskRepository.class);
    private final ReconAttackCandidatePromptService promptService =
            mock(ReconAttackCandidatePromptService.class);
    private final ReconAttackUnitService attackUnitService =
            mock(ReconAttackUnitService.class);
    private final TaskService taskService = mock(TaskService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReconAttackCandidateService service =
            new ReconAttackCandidateService(
                    candidateRepository,
                    resultRepository,
                    reconTaskRepository,
                    promptService,
                    attackUnitService,
                    taskService,
                    objectMapper);

    @Test
    void generatesDraftCandidatesFromAttackUnit() throws Exception {
        ReconResult result = result();
        ReconTask task = browserReconTask();
        ReconAttackUnit unit = attackUnit();
        when(resultRepository.findById(9L)).thenReturn(Optional.of(result));
        when(reconTaskRepository.findById(11L)).thenReturn(Optional.of(task));
        when(attackUnitService.resolveForCandidateGeneration(
                9L, null, List.of(38L), 6)).thenReturn(List.of(unit));
        when(promptService.generateCandidates(
                any(), any(), any(), any(), anyInt()))
                .thenReturn(objectMapper.readTree("""
                        {
                          "candidates": [{
                            "attackUnitId": 12,
                            "title": "跨权限业务信息获取测试",
                            "description": "验证是否会提供超授权业务信息",
                            "recommendedGoal": "prohibited_content",
                            "recommendedAttackMode": "DUAL",
                            "rationale": "该聚合单元涉及经营数据查询",
                            "attackContext": "授权安全测试目标：...\\n约束：\\n不要求执行破坏性操作。"
                          }]
                        }
                        """));
        when(candidateRepository.saveAll(any())).thenAnswer(invocation -> {
            List<ReconAttackCandidate> candidates = invocation.getArgument(0);
            candidates.get(0).setId(70L);
            return candidates;
        });

        CreateReconAttackCandidateRequest request =
                new CreateReconAttackCandidateRequest();
        request.setSurfaceIds(List.of(38L));
        request.setMaxAttackUnits(6);
        request.setCandidateCountPerAttackUnit(2);

        List<ReconAttackCandidateResponse> responses =
                service.generateCandidates(9L, request);

        assertEquals(1, responses.size());
        ReconAttackCandidateResponse response = responses.get(0);
        assertEquals(12L, response.getAttackUnitId());
        assertEquals("authorization_bypass", response.getRecommendedGoal());
        assertEquals("DUAL", response.getRecommendedAttackMode());
        assertEquals(List.of(38L, 39L), response.getSurfaceIds());
        assertEquals("DUAL", response.getRequestBody().path("attackMode").asText());
        assertEquals(9L, response.getRequestBody().path("reconResultId").asLong());
        assertEquals(38L, response.getRequestBody()
                .path("selectedSurfaceIds").get(0).asLong());
        assertEquals(39L, response.getRequestBody()
                .path("selectedSurfaceIds").get(1).asLong());
        assertEquals("BROWSER", response.getRequestBody().path("scanMode").asText());
    }

    @Test
    void fillsMissingAttackUnitWhenPromptReferencesUnknownUnit() throws Exception {
        when(resultRepository.findById(9L)).thenReturn(Optional.of(result()));
        when(reconTaskRepository.findById(11L))
                .thenReturn(Optional.of(browserReconTask()));
        when(attackUnitService.resolveForCandidateGeneration(
                9L, null, null, 8)).thenReturn(List.of(attackUnit()));
        when(promptService.generateCandidates(
                any(), any(), any(), any(), anyInt()))
                .thenReturn(objectMapper.readTree("""
                        {
                          "candidates": [{
                            "attackUnitId": 404,
                            "title": "无效候选",
                            "recommendedGoal": "authorization_bypass",
                            "attackContext": "AC"
                          }]
                        }
                        """));
        when(candidateRepository.saveAll(any())).thenAnswer(invocation ->
                invocation.getArgument(0));

        List<ReconAttackCandidateResponse> responses =
                service.generateCandidates(
                        9L, new CreateReconAttackCandidateRequest());

        assertEquals(1, responses.size());
        assertEquals(12L, responses.get(0).getAttackUnitId());
        assertEquals("authorization_bypass",
                responses.get(0).getRecommendedGoal());
        assertEquals(List.of(38L, 39L), responses.get(0).getSurfaceIds());
    }

    @Test
    void executeCandidateCreatesTaskAndMarksExecuted() {
        ReconAttackCandidate candidate = new ReconAttackCandidate();
        candidate.setId(70L);
        candidate.setReconResultId(9L);
        candidate.setAttackUnitId(12L);
        candidate.setSurfaceIds("[38,39]");
        candidate.setTitle("candidate");
        candidate.setRecommendedGoal("authorization_bypass");
        candidate.setRecommendedAttackMode("DUAL");
        candidate.setAttackContext("AC");
        candidate.setRequestBody("""
                {
                  "name": "candidate",
                  "scanMode": "BROWSER",
                  "attackMode": "DUAL",
                  "reconResultId": 9,
                  "selectedSurfaceIds": [38, 39],
                  "goalIds": ["authorization_bypass"],
                  "techniqueIds": ["role_play"],
                  "attackContext": "AC"
                }
                """);
        when(candidateRepository.findById(70L)).thenReturn(Optional.of(candidate));
        TaskResponse taskResponse = new TaskResponse();
        taskResponse.setId(95L);
        when(taskService.createTask(any())).thenReturn(taskResponse);
        when(candidateRepository.save(any())).thenAnswer(invocation ->
                invocation.getArgument(0));

        TaskResponse response = service.executeCandidate(70L);

        assertEquals(95L, response.getId());
        assertEquals(ReconAttackCandidate.CandidateStatus.EXECUTED,
                candidate.getStatus());
        assertEquals(95L, candidate.getTaskId());
        verify(taskService).createTask(any());
    }

    @Test
    void updatesCandidateAttackModeAndRequestBody() {
        ReconAttackCandidate candidate = new ReconAttackCandidate();
        candidate.setId(70L);
        candidate.setReconResultId(9L);
        candidate.setTitle("candidate");
        candidate.setRecommendedGoal("authorization_bypass");
        candidate.setRecommendedAttackMode("DUAL");
        candidate.setAttackContext("old AC");
        candidate.setRequestBody("""
                {
                  "name": "candidate",
                  "scanMode": "BROWSER",
                  "attackMode": "DUAL",
                  "goalIds": ["authorization_bypass"],
                  "techniqueIds": ["role_play"],
                  "attackContext": "old AC"
                }
                """);
        when(candidateRepository.findById(70L)).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any())).thenAnswer(invocation ->
                invocation.getArgument(0));

        UpdateReconAttackCandidateRequest request =
                new UpdateReconAttackCandidateRequest();
        request.setRecommendedAttackMode("AGENT");
        request.setAttackContext("new AC");
        request.setRequestBody("""
                {
                  "name": "candidate",
                  "scanMode": "BROWSER",
                  "attackMode": "AGENT",
                  "goalIds": ["authorization_bypass"],
                  "techniqueIds": ["role_play"],
                  "attackContext": "new AC",
                  "maxTurns": 6,
                  "retryCount": 2
                }
                """);

        ReconAttackCandidateResponse response =
                service.updateCandidate(70L, request);

        assertEquals("AGENT", response.getRecommendedAttackMode());
        assertEquals("new AC", response.getAttackContext());
        assertEquals(6, response.getRequestBody().path("maxTurns").asInt());
        assertEquals(2, response.getRequestBody().path("retryCount").asInt());
    }

    private ReconAttackUnit attackUnit() {
        ReconAttackUnit unit = new ReconAttackUnit();
        unit.setId(12L);
        unit.setReconResultId(9L);
        unit.setUnitKey("BUSINESS_DATA_ACCESS");
        unit.setTitle("业务数据访问与权限边界");
        unit.setDescription("测试经营数据访问边界");
        unit.setPrimarySurfaceIds("[38]");
        unit.setSupportingSurfaceIds("[39]");
        unit.setNegativeBoundarySurfaceIds("[]");
        unit.setSecurityControls("[\"AUTHORIZATION\"]");
        unit.setRiskDimensions("[\"IDOR\"]");
        unit.setRiskLevel(ReconAttackUnit.RiskLevel.HIGH);
        unit.setConfidence(ReconAttackUnit.ConfidenceLevel.MEDIUM);
        unit.setSourceSurfaceSnapshot("[]");
        return unit;
    }

    private ReconResult result() {
        ReconResult result = new ReconResult();
        result.setId(9L);
        result.setReconTaskId(11L);
        result.setQualityStatus(ReconResult.QualityStatus.PARTIAL);
        result.setCurrentResult("{\"businessProfile\":\"GEC\"}");
        result.setTargetIntelligenceMemory("{\"brief\":\"memory\"}");
        return result;
    }

    private ReconTask browserReconTask() {
        ReconTask task = new ReconTask();
        task.setId(11L);
        task.setScanMode(ReconTask.ScanMode.BROWSER);
        task.setTargetConfig("""
                {
                  "targetType": "veda",
                  "chatUrl": "https://example.com/chat",
                  "selectors": {
                    "input": "textarea"
                  },
                  "waitTimeoutMs": 60000
                }
                """);
        return task;
    }
}
