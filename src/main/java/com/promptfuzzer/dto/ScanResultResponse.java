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

    public static ScanResultResponse from(ScanResult result, com.promptfuzzer.entity.ScanPayload payload) {
        ScanResultResponse r = new ScanResultResponse();
        r.setId(result.getId());
        r.setPayloadId(result.getPayloadId());
        r.setTaskId(result.getTaskId());
        r.setVerdict(result.getVerdict() != null ? result.getVerdict().name() : null);
        r.setHarmType(result.getHarmType());
        r.setEvidence(result.getEvidence());
        r.setResponseTime(result.getResponseTime());
        String extracted = result.getExtractedText();
        if (extracted != null) {
            r.setExtractedTextLength(extracted.length());
            r.setExtractedTextPreview(extracted.length() <= 500
                    ? extracted
                    : extracted.substring(0, 500) + "…");
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
}
