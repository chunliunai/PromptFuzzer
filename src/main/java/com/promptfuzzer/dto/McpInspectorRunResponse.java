package com.promptfuzzer.dto;

import lombok.Builder;
import lombok.Data;

/**
 * MCP Inspector CLI 一次性验证结果（CVE-2026-119025）。
 *
 * <p>后端直接以 {@code --cli --method initialize} 调起钉版本 Inspector，
 * 在 PT 页面内完成完整 OAuth 握手，无需跳转 Inspector Web UI。
 */
@Data
@Builder
public class McpInspectorRunResponse {

    private String variant;
    private String version;
    private String transport;
    private String serverUrl;

    /** 实际执行的本地命令。 */
    private String command;

    /** 进程退出码；超时强杀时为 null。 */
    private Integer exitCode;

    /** 是否因等待超时被强制结束（OAuth 回调不会完成，属预期现象）。 */
    private boolean timedOut;

    /** 耗时（毫秒）。 */
    private long durationMs;

    /** Inspector CLI 合并后的标准输出/错误输出。 */
    private String output;
}
