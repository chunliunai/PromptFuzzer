package com.promptfuzzer.config;

import lombok.Data;

import java.util.Map;

@Data
public class BrowserTargetConfig {

    /** 目标聊天页面 URL */
    private String chatUrl;

    /** CSS 选择器: { input, submit, responseArea, newChat } */
    private Map<String, String> selectors;

    /** 登录配置（airedlab 目前为 null，后续扩展用） */
    private Map<String, String> login;

    /** 等待 AI 回复超时 (ms)，默认 15000 */
    private int waitTimeoutMs = 45000;

    /** 目标类型标识，用于认证文件命名，如 "airedlab" */
    private String targetType = "default";
}
