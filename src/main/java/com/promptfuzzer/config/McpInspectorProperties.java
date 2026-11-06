package com.promptfuzzer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MCP Inspector 实验台配置（CVE-2026-119025 复现用）。
 *
 * <p>全部为纯新增配置，缺省值指向仓库内 labs 钉版本安装目录，不影响现有功能。
 */
@Data
@Component
@ConfigurationProperties(prefix = "promptfuzzer.mcp-inspector")
public class McpInspectorProperties {

    /** 启动 Inspector 使用的 node 可执行文件（Windows 上需在服务进程 PATH 中，或配绝对路径）。 */
    private String nodeCommand = "node";

    /** labs 根目录；相对路径基于进程工作目录（user.dir）解析。 */
    private String labsRoot = "labs/cve-2026-119025-mcp-inspector-rce";

    /** 等待 Inspector Web UI 启动的超时秒数。 */
    private int startTimeoutSeconds = 20;
}
