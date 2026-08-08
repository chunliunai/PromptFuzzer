package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.dto.*;
import com.promptfuzzer.entity.ReconApplicationSurface;
import com.promptfuzzer.entity.ReconResult;
import com.promptfuzzer.entity.ReconTask;
import com.promptfuzzer.repository.ReconApplicationSurfaceRepository;
import com.promptfuzzer.repository.ReconAttackCandidateRepository;
import com.promptfuzzer.repository.ReconAttackUnitRepository;
import com.promptfuzzer.repository.ReconResultRepository;
import com.promptfuzzer.repository.ReconTaskRepository;
import com.promptfuzzer.repository.ReconTraceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ReconTaskService {

    private final ReconTaskRepository taskRepository;
    private final ReconResultRepository resultRepository;
    private final ReconTraceRepository traceRepository;
    private final ReconApplicationSurfaceRepository surfaceRepository;
    private final ReconAttackUnitRepository attackUnitRepository;
    private final ReconAttackCandidateRepository attackCandidateRepository;
    private final ReconExecutor reconExecutor;
    private final ObjectMapper objectMapper;

    public ReconTaskResponse createTask(CreateReconTaskRequest request) {
        validateCreateRequest(request);
        ReconTask task = new ReconTask();
        task.setName(request.getName().trim());
        task.setScanMode(ReconTask.ScanMode.valueOf(
                request.getScanMode().toUpperCase(Locale.ROOT)));
        task.setReconContext(request.getReconContext());
        task.setExternalIntelligence(request.getExternalIntelligence());
        task.setRawRequestTemplate(request.getRawRequestTemplate());
        try {
            if (request.getTargetConfig() != null) {
                task.setTargetConfig(objectMapper.writeValueAsString(request.getTargetConfig()));
            }
            CreateReconTaskRequest.ReconExecutionConfig config =
                    request.getReconConfig() != null
                            ? request.getReconConfig()
                            : new CreateReconTaskRequest.ReconExecutionConfig();
            int maxOpenDiscoveryMessages =
                    config.getMaxOpenDiscoveryMessages() != null
                            ? config.getMaxOpenDiscoveryMessages() : 1;
            int maxDiscoveryMessages = config.getMaxDiscoveryMessages() != null
                    ? config.getMaxDiscoveryMessages() : 4;
            int maxGapFollowups = config.getMaxGapFollowups() != null
                    ? config.getMaxGapFollowups() : 3;
            int maxVerifications = config.getMaxCapabilityVerifications() != null
                    ? config.getMaxCapabilityVerifications() : 6;
            config.setOpenDiscoveryEnabled(
                    config.getOpenDiscoveryEnabled() == null
                            || Boolean.TRUE.equals(config.getOpenDiscoveryEnabled()));
            config.setMaxOpenDiscoveryMessages(
                    Math.max(0, Math.min(maxOpenDiscoveryMessages, 1)));
            config.setMaxDiscoveryMessages(
                    Math.max(1, Math.min(maxDiscoveryMessages, 6)));
            config.setMaxGapFollowups(
                    Math.max(0, Math.min(maxGapFollowups, 4)));
            config.setMaxCapabilityVerifications(
                    Math.max(0, Math.min(maxVerifications, 8)));
            task.setReconConfig(objectMapper.writeValueAsString(config));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid RECON configuration: " + e.getMessage(), e);
        }
        task = taskRepository.save(task);
        reconExecutor.execute(task.getId());
        return ReconTaskResponse.from(task);
    }

    public List<ReconTaskResponse> listTasks() {
        return taskRepository.findAll().stream()
                .map(ReconTaskResponse::from)
                .toList();
    }

    public ReconTaskResponse getTask(Long taskId) {
        return ReconTaskResponse.from(requireTask(taskId));
    }

    public ReconResultResponse getResultByTaskId(Long taskId) {
        requireTask(taskId);
        ReconResult result = resultRepository.findByReconTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "ReconResult not ready for task: " + taskId));
        return ReconResultResponse.from(result);
    }

    public List<ReconTraceResponse> getTraces(Long taskId) {
        requireTask(taskId);
        return traceRepository.findByReconTaskIdOrderByIdAsc(taskId).stream()
                .map(ReconTraceResponse::from)
                .toList();
    }

    @Transactional
    public void deleteTask(Long taskId) {
        ReconTask task = requireTask(taskId);
        if (task.getStatus() == ReconTask.Status.PENDING
                || task.getStatus() == ReconTask.Status.RUNNING) {
            throw new IllegalArgumentException(
                    "Running RECON task cannot be deleted: " + taskId);
        }

        resultRepository.findByReconTaskId(taskId).ifPresent(result -> {
            attackCandidateRepository.deleteByReconResultId(result.getId());
            attackUnitRepository.deleteByReconResultId(result.getId());
            surfaceRepository.deleteByReconResultId(result.getId());
            resultRepository.delete(result);
        });
        traceRepository.deleteByReconTaskId(taskId);
        taskRepository.delete(task);
    }

    public List<ReconApplicationSurfaceResponse> getApplicationSurfaces(Long reconResultId) {
        requireResult(reconResultId);
        return surfaceRepository
                .findByReconResultIdAndDeletedFalseOrderByIdAsc(reconResultId)
                .stream()
                .map(ReconApplicationSurfaceResponse::from)
                .toList();
    }

    @Transactional
    public ReconApplicationSurfaceResponse addApplicationSurface(
            Long reconResultId,
            UpdateReconApplicationSurfaceRequest request) {
        requireResult(reconResultId);
        requireTitle(request.getTitle());
        ReconApplicationSurface surface = new ReconApplicationSurface();
        surface.setReconResultId(reconResultId);
        surface.setOriginalTitle(request.getTitle().trim());
        surface.setOriginalDescription(request.getDescription());
        surface.setCurrentTitle(request.getTitle().trim());
        surface.setCurrentDescription(request.getDescription());
        surface.setParentSurfaceId(validateParentSurface(
                reconResultId, null, request.getParentSurfaceId()));
        surface.setCapabilityKey(request.getCapabilityKey());
        surface.setSurfaceLevel(parseSurfaceLevel(request.getSurfaceLevel()));
        surface.setSelectable(request.getSelectable() != null
                ? request.getSelectable()
                : surface.getSurfaceLevel()
                == ReconApplicationSurface.SurfaceLevel.CAPABILITY);
        surface.setSurfaceType(request.getSurfaceType());
        surface.setRelatedTool(request.getRelatedTool());
        surface.setSupportedActions(request.getSupportedActions());
        surface.setResourceScope(request.getResourceScope());
        surface.setEvidenceSource(ReconApplicationSurface.EvidenceSource.EXTERNAL_EVIDENCE);
        surface.setVerificationStatus(parseVerificationStatus(request.getVerificationStatus()));
        surface.setSourceType(ReconApplicationSurface.SourceType.USER_ADDED);
        surface.setUserEdited(true);
        return ReconApplicationSurfaceResponse.from(surfaceRepository.save(surface));
    }

    @Transactional
    public ReconApplicationSurfaceResponse updateApplicationSurface(
            Long reconResultId,
            Long surfaceId,
            UpdateReconApplicationSurfaceRequest request) {
        ReconApplicationSurface surface = requireSurface(reconResultId, surfaceId);
        if (request.getTitle() != null) {
            requireTitle(request.getTitle());
            surface.setCurrentTitle(request.getTitle().trim());
        }
        if (request.getParentSurfaceId() != null) {
            surface.setParentSurfaceId(validateParentSurface(
                    reconResultId, surfaceId, request.getParentSurfaceId()));
        }
        if (request.getCapabilityKey() != null) {
            surface.setCapabilityKey(request.getCapabilityKey());
        }
        if (request.getSurfaceLevel() != null) {
            ReconApplicationSurface.SurfaceLevel nextLevel =
                    parseSurfaceLevel(request.getSurfaceLevel());
            if (nextLevel == ReconApplicationSurface.SurfaceLevel.CAPABILITY
                    && surface.getSurfaceLevel()
                    == ReconApplicationSurface.SurfaceLevel.DOMAIN
                    && !surfaceRepository
                    .findByParentSurfaceIdAndDeletedFalseOrderByIdAsc(surfaceId)
                    .isEmpty()) {
                throw new IllegalArgumentException(
                        "DOMAIN surface with active children cannot become CAPABILITY");
            }
            surface.setSurfaceLevel(nextLevel);
        }
        if (request.getSelectable() != null) {
            surface.setSelectable(request.getSelectable());
        }
        if (request.getDescription() != null) {
            surface.setCurrentDescription(request.getDescription());
        }
        if (request.getSurfaceType() != null) {
            surface.setSurfaceType(request.getSurfaceType());
        }
        if (request.getRelatedTool() != null) {
            surface.setRelatedTool(request.getRelatedTool());
        }
        if (request.getSupportedActions() != null) {
            surface.setSupportedActions(request.getSupportedActions());
        }
        if (request.getResourceScope() != null) {
            surface.setResourceScope(request.getResourceScope());
        }
        if (request.getVerificationStatus() != null) {
            surface.setVerificationStatus(parseVerificationStatus(
                    request.getVerificationStatus()));
        }
        surface.setUserEdited(true);
        return ReconApplicationSurfaceResponse.from(surfaceRepository.save(surface));
    }

    @Transactional
    public void deleteApplicationSurface(Long reconResultId, Long surfaceId) {
        ReconApplicationSurface surface = requireSurface(reconResultId, surfaceId);
        surface.setDeleted(true);
        surface.setUserEdited(true);
        surfaceRepository.save(surface);
        if (surface.getSurfaceLevel()
                == ReconApplicationSurface.SurfaceLevel.DOMAIN) {
            List<ReconApplicationSurface> children = surfaceRepository
                    .findByParentSurfaceIdAndDeletedFalseOrderByIdAsc(surfaceId);
            for (ReconApplicationSurface child : children) {
                child.setDeleted(true);
                child.setUserEdited(true);
            }
            surfaceRepository.saveAll(children);
        }
    }

    private void validateCreateRequest(CreateReconTaskRequest request) {
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        ReconTask.ScanMode scanMode;
        try {
            scanMode = ReconTask.ScanMode.valueOf(
                    request.getScanMode().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("scanMode must be BROWSER or HTTP_TEMPLATE");
        }
        if (scanMode == ReconTask.ScanMode.BROWSER && request.getTargetConfig() == null) {
            throw new IllegalArgumentException("targetConfig is required for BROWSER RECON");
        }
        if (scanMode == ReconTask.ScanMode.BROWSER
                && (request.getTargetConfig().getChatUrl() == null
                || request.getTargetConfig().getChatUrl().isBlank())) {
            throw new IllegalArgumentException("targetConfig.chatUrl is required");
        }
        if (scanMode == ReconTask.ScanMode.HTTP_TEMPLATE) {
            validateHttpTemplate(request.getRawRequestTemplate());
        }
    }

    private void validateHttpTemplate(String template) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException(
                    "rawRequestTemplate is required for HTTP_TEMPLATE RECON");
        }
        String[] lines = template.split("\\r?\\n");
        if (lines.length == 0
                || !lines[0].matches("^[A-Z]+\\s+\\S+\\s+HTTP/\\d(?:\\.\\d)?$")) {
            throw new IllegalArgumentException(
                    "rawRequestTemplate must start with a valid HTTP request line");
        }
        boolean hasHost = false;
        for (String line : lines) {
            if (line.regionMatches(true, 0, "Host:", 0, 5)
                    && !line.substring(5).trim().isBlank()) {
                hasHost = true;
                break;
            }
        }
        if (!hasHost) {
            throw new IllegalArgumentException(
                    "rawRequestTemplate must contain a Host header");
        }
        if (!template.contains("{{PAYLOAD}}")) {
            throw new IllegalArgumentException(
                    "rawRequestTemplate must contain {{PAYLOAD}}");
        }
    }

    private ReconTask requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("ReconTask not found: " + taskId));
    }

    private ReconResult requireResult(Long resultId) {
        return resultRepository.findById(resultId)
                .orElseThrow(() -> new IllegalArgumentException("ReconResult not found: " + resultId));
    }

    private ReconApplicationSurface requireSurface(Long resultId, Long surfaceId) {
        ReconApplicationSurface surface = surfaceRepository.findById(surfaceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "ReconApplicationSurface not found: " + surfaceId));
        if (!resultId.equals(surface.getReconResultId()) || Boolean.TRUE.equals(surface.getDeleted())) {
            throw new IllegalArgumentException(
                    "ReconApplicationSurface does not belong to result: " + resultId);
        }
        return surface;
    }

    private void requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
    }

    private Long validateParentSurface(
            Long resultId,
            Long surfaceId,
            Long parentSurfaceId) {
        if (parentSurfaceId == null) {
            return null;
        }
        if (parentSurfaceId.equals(surfaceId)) {
            throw new IllegalArgumentException(
                    "parentSurfaceId cannot reference the same surface");
        }
        ReconApplicationSurface parent = requireSurface(resultId, parentSurfaceId);
        if (parent.getSurfaceLevel()
                != ReconApplicationSurface.SurfaceLevel.DOMAIN) {
            throw new IllegalArgumentException(
                    "parentSurfaceId must reference a DOMAIN surface");
        }
        return parentSurfaceId;
    }

    private ReconApplicationSurface.SurfaceLevel parseSurfaceLevel(String value) {
        if (value == null || value.isBlank()) {
            return ReconApplicationSurface.SurfaceLevel.CAPABILITY;
        }
        try {
            return ReconApplicationSurface.SurfaceLevel.valueOf(
                    value.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid surfaceLevel: " + value);
        }
    }

    private ReconApplicationSurface.VerificationStatus parseVerificationStatus(String value) {
        if (value == null || value.isBlank()) {
            return ReconApplicationSurface.VerificationStatus.UNVERIFIED;
        }
        try {
            return ReconApplicationSurface.VerificationStatus.valueOf(
                    value.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid verificationStatus: " + value);
        }
    }
}
