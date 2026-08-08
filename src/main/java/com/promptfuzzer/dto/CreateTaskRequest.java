package com.promptfuzzer.dto;

import com.promptfuzzer.config.BrowserTargetConfig;
import lombok.Data;

import java.util.List;

@Data
public class CreateTaskRequest {

    private String name;
    private String scanMode = "HTTP_TEMPLATE";
    private String rawRequestTemplate;
    private String promptFieldPath;
    private String attackContext;
    private String externalIntelligence;
    private ReconConfig reconConfig;
    private Long reconResultId;
    private Boolean supplementalRecon = false;
    private List<Long> selectedSurfaceIds;

    private List<String> goalIds;
    private List<String> techniqueIds;
    private int generateCount = 3;

    private String attackMode = "SINGLE_TURN";
    private Integer maxTurns = 5;
    private Integer retryCount = 0;

    /** 浏览器模式专用：目标网站配置（scanMode=BROWSER 时必填） */
    private BrowserTargetConfig targetConfig;
}
