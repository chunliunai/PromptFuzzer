package com.promptfuzzer.dto;

import com.promptfuzzer.config.BrowserTargetConfig;
import lombok.Data;

@Data
public class BrowserTargetProfileRequest {

    private String targetType;
    private String displayName;
    private BrowserTargetConfig targetConfig;
}
