package com.promptfuzzer.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** MCP Inspector 会话状态。 */
@Data
@Builder
public class McpInspectorSessionResponse {

    private boolean running;
    /** vulnerable / fixed */
    private String variant;
    /** 对应 Inspector 语义版本 */
    private String version;
    private String transport;
    private String serverUrl;
    private Integer port;
    /** 含一次性访问 token 的完整 Web UI 地址 */
    private String uiUrl;
    private String token;
    private String launchCommand;
    private List<String> logTail;
    private String error;
}
