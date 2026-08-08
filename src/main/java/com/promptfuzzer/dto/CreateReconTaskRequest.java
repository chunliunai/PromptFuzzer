package com.promptfuzzer.dto;

import com.promptfuzzer.config.BrowserTargetConfig;
import lombok.Data;

@Data
public class CreateReconTaskRequest {

    private String name;
    private String scanMode = "BROWSER";
    private String reconContext;
    private String externalIntelligence;
    private BrowserTargetConfig targetConfig;
    private String rawRequestTemplate;
    private ReconExecutionConfig reconConfig = new ReconExecutionConfig();

    @Data
    public static class ReconExecutionConfig {
        private Boolean openDiscoveryEnabled = true;
        private Integer maxOpenDiscoveryMessages = 1;
        private Integer maxDiscoveryMessages = 4;
        private Integer maxGapFollowups = 3;
        private Integer maxCapabilityVerifications = 6;
        private Boolean cleanBetweenVerifications = true;
    }
}
