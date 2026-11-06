package com.promptfuzzer.dto;

import com.promptfuzzer.entity.ScanResult;
import lombok.Data;

@Data
public class ScanResultResponse {

    private Long id;
    private Long payloadId;
    private Long taskId;
    private String goal;
    private String technique;
    private String strategyId;
    private String templateName;
    private String payloadContent;
    private String verdict;
    private String harmType;
    private String evidence;
    private String extractedTextPreview;
    private Integer extractedTextLength;
    private Integer responseTime;
    private String requestBodyPreview;
    private Integer requestBodyLength;
    private String responseBodyPreview;
    private Integer responseBodyLength;
    private String executionChannel;
    private Long replayProfileId;
    private String replayProfileClassification;
    private Integer statusCode;
    private String contentType;
    private Integer targetChatIndex;

    public static ScanResultResponse from(ScanResult result, com.promptfuzzer.entity.ScanPayload payload) {
        ScanResultResponse r = new ScanResultResponse();
        r.setId(result.getId());
        r.setPayloadId(result.getPayloadId());
        r.setTaskId(result.getTaskId());
        r.setVerdict(result.getVerdict() != null ? result.getVerdict().name() : null);
        r.setHarmType(result.getHarmType());
        r.setEvidence(result.getEvidence());
        r.setResponseTime(result.getResponseTime());
        r.setExecutionChannel(result.getExecutionChannel());
        r.setReplayProfileId(result.getReplayProfileId());
        r.setReplayProfileClassification(result.getReplayProfileClassification());
        r.setStatusCode(result.getStatusCode());
        r.setContentType(result.getContentType());
        r.setTargetChatIndex(result.getTargetChatIndex());
        String extracted = result.getExtractedText();
        if (extracted != null) {
            r.setExtractedTextLength(extracted.length());
            r.setExtractedTextPreview(preview(extracted));
        }
        if (result.getRequestBody() != null) {
            r.setRequestBodyLength(result.getRequestBody().length());
            r.setRequestBodyPreview(preview(result.getRequestBody()));
        }
        if (result.getResponseBody() != null) {
            r.setResponseBodyLength(result.getResponseBody().length());
            r.setResponseBodyPreview(preview(result.getResponseBody()));
        }
        if (payload != null) {
            r.setGoal(payload.getGoal());
            r.setTechnique(payload.getTechnique());
            r.setStrategyId(payload.getStrategyId());
            r.setTemplateName(payload.getTemplateName());
            r.setPayloadContent(payload.getContent());
        }
        return r;
    }

    private static String preview(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "…";
    }
}
