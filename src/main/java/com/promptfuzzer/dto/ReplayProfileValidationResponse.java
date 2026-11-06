package com.promptfuzzer.dto;

import com.promptfuzzer.entity.ReplayProfile;
import lombok.Data;

@Data
public class ReplayProfileValidationResponse {
    private Long profileId;
    private Long taskId;
    private String classification;
    private boolean success;
    private int statusCode;
    private String contentType;
    private int rawResponseChars;
    private String extractedTextPreview;
    private String failureReason;

    public static ReplayProfileValidationResponse of(ReplayProfile profile,
                                                     boolean success,
                                                     int statusCode,
                                                     String contentType,
                                                     String rawResponse,
                                                     String extractedText,
                                                     String failureReason) {
        ReplayProfileValidationResponse response = new ReplayProfileValidationResponse();
        response.setProfileId(profile.getId());
        response.setTaskId(profile.getTaskId());
        response.setClassification(profile.getClassification().name());
        response.setSuccess(success);
        response.setStatusCode(statusCode);
        response.setContentType(contentType);
        response.setRawResponseChars(rawResponse != null ? rawResponse.length() : 0);
        response.setExtractedTextPreview(preview(extractedText, 500));
        response.setFailureReason(failureReason);
        return response;
    }

    private static String preview(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > maxChars ? oneLine.substring(0, maxChars) + "..." : oneLine;
    }
}
