package com.promptfuzzer.dto;

import lombok.Data;

/**
 * 启动 MCP Inspector 会话请求。
 *
 * @param variant   版本变体：vulnerable(2.11.0) / fixed(2.12.0)
 * @param transport 传输类型：http / sse（stdio 由 Inspector 界面内配置）
 * @param serverUrl 目标 MCP Server URL，如 http://127.0.0.1:8787/mcp
 */
@Data
public class McpInspectorStartRequest {

    private String variant = "vulnerable";
    private String transport = "http";
    private String serverUrl;
}
