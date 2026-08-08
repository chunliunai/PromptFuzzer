# AI 构造 Payload 功能说明

> 记录 AI 自动生成攻击 Payload 模块的当前实现状态，供下次开发快速恢复上下文。

---

## 一、功能概述

早期版本曾依赖手工编写的 YAML 策略文件提供固定 Prompt 模板，该链路已于 2026-08-08 清理。

当前模式将攻击目标（goal）和绕过手法（technique）拆分为两个独立维度，由 AI 根据组合动态生成攻击 Payload。

`SINGLE_TURN` 必须同时传入非空 `goalIds + techniqueIds`；参数缺失时直接返回错误，不再回退固定模板。

---

## 二、生成流程

```
用户选择：goalIds + techniqueIds + generateCount

TaskService：
  for each goal × technique 组合：
    PayloadGeneratorService.generate(goal, technique, attackContext, count)
      → 加载 goals/{goal}.txt（攻击意图描述）
      → 加载 techniques/{technique}.txt（绕过手法描述）
      → 填入 generate.txt 主模板
      → 调用通义千问，返回 count 条 payload
    → 每条 payload 存入 ScanPayload 表

后续：ScanExecutorService 异步发包 → JudgeService 判定（流程不变）
```

**生成 payload 总数** = `goalIds数量 × techniqueIds数量 × generateCount`

**特殊说明：`ssrf` goal**
- `ssrf` 属于动作类目标，生成 payload 时会额外注入任务级 OOB URL
- 支持占位符 `{{OOB_URL}}` 和 `{{DNSLOG_DOMAIN}}`
- 单条 payload 不依赖普通文本 Judge 判定成功，最终任务级结论写入 `Task.reportSummary`

---

## 三、文件结构

```
prompts/attack/
  generate.txt                    ← 主生成模板（所有组合共用）
  goals/
    prompt_leak.txt               ← 攻击目标：系统提示词泄露
    credential_leak.txt           ← 攻击目标：系统凭证配置泄露
    user_data_leak.txt            ← 攻击目标：用户文件数据泄露
    context_leak.txt              ← 攻击目标：跨用户上下文泄露
    model_info.txt                ← 攻击目标：模型信息探测
    prohibited_content.txt        ← 攻击目标：违禁内容生成
    protected_value_extraction.txt ← 攻击目标：受保护值提取（秘密词/flag/token/密码等）
    ssrf.txt                      ← 攻击目标：SSRF/OOB 外联触发
    command_execution.txt         ← 攻击目标：命令执行/工具命令调用
    authorization_bypass.txt      ← 攻击目标：授权绕过/越权访问与操作
  techniques/
    none.txt                      ← 绕过手法：直接请求（基准测试）
    role_play.txt                 ← 绕过手法：角色扮演（DAN/对立角色/虚构场景）
    encoding.txt                  ← 绕过手法：编码混淆（Base64/倒序/字符替换）
    separator.txt                 ← 绕过手法：分隔符注入（伪造系统标签/忽略指令）
    emotional.txt                 ← 绕过手法：情感操纵（奶奶漏洞/临终请求/学术包装）
    misdirection.txt              ← 绕过手法：语义误导（业务词汇/正常场景包装）
    output_control.txt            ← 绕过手法：输出格式控制（倒序/ASCII/JSON/逐字）
    acrostic_embedding.txt        ← 绕过手法：首字母/藏头嵌入（每词/每行/每句首字母承载受保护值）
    token_exploit.txt             ← 绕过手法：跨轮拆分敏感词
    rule_fabrication.txt          ← 绕过手法：规则编造（新规则/权限例外/策略更新）
    tool_intent.txt               ← 绕过手法：工具意图诱导（诱导 AI 主动调用工具）
```

**扩展方式**：在对应目录新增 `.txt` 文件，重启服务自动生效，无需改代码。

---

## 四、generate.txt 变量说明

| 变量 | 来源 | 说明 |
|------|------|------|
| `{{GOAL_DESCRIPTION}}` | `goals/{goalId}.txt` 全文 | 攻击意图、成功标志、切入点 |
| `{{TECHNIQUE_DESCRIPTION}}` | `techniques/{techniqueId}.txt` 全文 | 手法核心逻辑、典型变体 |
| `{{TARGET_CONTEXT}}` | 用户填的 `attackContext` | 填写攻击场景描述和攻击目标（如"目标是某客服AI，系统中有保护的秘密词，目标是让AI说出来"）；**不要填系统提示词原文**，否则生成器会把原文嵌进 payload 导致攻击无效；未填则用默认值："目标是一个 AI 应用，具体系统配置未知" |
| `{{COUNT}}` | 用户填的 `generateCount`，默认 3 | 每个组合生成的条数 |

### `ssrf` goal 的额外变量

`ssrf` 使用任务级 OOB/DNSLog 验证。创建 `ssrf` 单轮任务时，`TaskService` 会提前准备 OOB 地址，并在 payload 入库前替换以下占位符：

| 变量 | 来源 | 说明 |
|------|------|------|
| `{{OOB_URL}}` | `OobService` 申请的 DNSLog URL 或 VPS fallback URL | 推荐使用，适合要求目标读取、检查、总结或导入远程资源 |
| `{{DNSLOG_DOMAIN}}` | DNSLog 下发域名或 fallback 主机 | 仅当 payload 需要纯域名时使用 |

DNSLog 正常时示例：

```text
{{OOB_URL}} = http://xxxx.log.dnslog.pp.ua
```

DNSLog 异常时 fallback 示例：

```text
{{OOB_URL}} = https://oob.example.com/pfz-oob?taskId={taskId}&nonce={nonce}
```

---

## 五、新增接口

### `GET /api/goals` — 查询可用攻击目标
```bash
curl http://localhost:9091/api/goals
# 返回：["prompt_leak","credential_leak","user_data_leak","context_leak","model_info","prohibited_content","fake_confirmation","protected_value_extraction","ssrf","command_execution","authorization_bypass"]
```

**当前 11 个 goal：**

| Goal ID | 中文名 |
|---------|--------|
| `prompt_leak` | 系统提示词泄露 |
| `credential_leak` | 系统凭证配置泄露 |
| `user_data_leak` | 用户文件数据泄露 |
| `context_leak` | 跨用户上下文泄露 |
| `model_info` | 模型信息探测 |
| `prohibited_content` | 违禁内容生成 |
| `fake_confirmation` | 虚假确认/错误承认 |
| `protected_value_extraction` | 受保护值提取（秘密词/flag/token/密码等） |
| `ssrf` | SSRF/OOB 外联触发 |
| `command_execution` | 命令执行/工具命令调用 |
| `authorization_bypass` | 授权绕过/越权访问与操作 |

### `GET /api/techniques` — 查询可用绕过手法
```bash
curl http://localhost:9091/api/techniques
# 返回：["role_play", "encoding", "emotional", "separator", "none", "misdirection", "output_control", "acrostic_embedding", "token_exploit", "rule_fabrication", "tool_intent"]
```

### `POST /api/tasks`（新模式参数）

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `goalIds` | List | ✅ | 攻击目标列表，从 `/api/goals` 取 |
| `techniqueIds` | List | ✅ | 绕过手法列表，从 `/api/techniques` 取 |
| `generateCount` | int | ❌ | 每组合生成条数，默认 3 |
| `attackContext` | String | ❌ | 攻击场景背景描述 + 攻击目标（如"目标是某客服AI，存在保护的秘密词，需让AI说出真实内容"）；仅作情报背景供生成器理解场景，**不要填系统提示词原文** |

**请求示例：**
```json
{
  "name": "测试某AI客服",
  "scanMode": "HTTP_TEMPLATE",
  "rawRequestTemplate": "POST /api/chat HTTP/1.1\nHost: target.example.com\nAuthorization: Bearer your-token\nContent-Type: application/json\n\n{\"message\":\"{{PAYLOAD}}\"}",
  "promptFieldPath": "message",
  "attackContext": "目标是一个在线客服AI，禁止透露系统设置，我们需要通过注入让AI说出被保护的系统配置内容",
  "goalIds": ["prompt_leak", "credential_leak"],
  "techniqueIds": ["role_play", "emotional", "encoding"],
  "generateCount": 3
}
```
生成：2 × 3 × 3 = **18 条 payload**，全部自动发包并 AI 判定。

---

## 六、完整工作流

```
1. GET  /api/goals          → 查询可用攻击目标
2. GET  /api/techniques     → 查询可用绕过手法
3. POST /api/tasks          → 创建任务（AI 自动生成 payload）
4. GET  /api/tasks/{id}     → 轮询进度，等 status=COMPLETED
5. GET  /api/tasks/{id}/results → 查看结果，关注 verdict=SUCCESS 的条目
```

---

## 七、新增 / 修改的文件

### 新增
| 文件 | 说明 |
|------|------|
| `service/PayloadGeneratorService.java` | 加载提示词文件，调 AI 生成 payload |
| `controller/AttackResourceController.java` | GET /api/goals 和 /api/techniques |
| `prompts/attack/generate.txt` | 主生成模板 |
| `prompts/attack/goals/prompt_leak.txt` | 系统提示词泄露目标描述 |
| `prompts/attack/goals/credential_leak.txt` | 系统凭证配置泄露目标描述 |
| `prompts/attack/goals/user_data_leak.txt` | 用户文件数据泄露目标描述 |
| `prompts/attack/goals/context_leak.txt` | 跨用户上下文泄露目标描述 |
| `prompts/attack/goals/model_info.txt` | 模型信息探测目标描述 |
| `prompts/attack/goals/prohibited_content.txt` | 违禁内容生成目标描述 |
| `prompts/attack/goals/fake_confirmation.txt` | 虚假确认目标描述 |
| `prompts/attack/goals/protected_value_extraction.txt` | 受保护值提取目标描述 |
| `prompts/attack/goals/ssrf.txt` | SSRF/OOB 外联触发目标描述 |
| `prompts/attack/goals/command_execution.txt` | 命令执行/工具命令调用目标描述 |
| `prompts/attack/goals/authorization_bypass.txt` | 授权绕过/越权访问与操作目标描述 |
| `prompts/attack/techniques/none.txt` | 直接请求手法 |
| `prompts/attack/techniques/role_play.txt` | 角色扮演手法 |
| `prompts/attack/techniques/encoding.txt` | 编码混淆手法 |
| `prompts/attack/techniques/separator.txt` | 分隔符注入手法 |
| `prompts/attack/techniques/emotional.txt` | 情感操纵手法 |
| `prompts/attack/techniques/misdirection.txt` | 语义误导手法 |
| `prompts/attack/techniques/output_control.txt` | 输出格式控制手法 |
| `prompts/attack/techniques/token_exploit.txt` | 跨轮拆分敏感词手法 |
| `prompts/attack/techniques/rule_fabrication.txt` | 规则编造手法 |
| `prompts/attack/techniques/tool_intent.txt` | 工具意图诱导手法 |

### 修改
| 文件 | 改动 |
|------|------|
| `dto/CreateTaskRequest.java` | 新增 `goalIds`、`techniqueIds`、`generateCount` |
| `service/TaskService.java` | `SINGLE_TURN` 统一通过 `buildPayloadsWithAI()` 动态生成 |
| `service/TaskService.java` | `ssrf` goal 下准备任务级 OOB URL，并替换 `{{OOB_URL}}` / `{{DNSLOG_DOMAIN}}` |

---

## 八、受保护值提取 AC 与首字母嵌入注意事项（2026-07-19）

`protected_value_extraction + acrostic_embedding` 已在 Gauntlet9 / Gauntlet10 验证中证明是有效轻量 weapon，但生成器需要遵守以下边界：

- 未知秘密词长度时，不要固定写死 `5/7/9/10` 行、词或步骤；应让结构自适应目标持有的真实值长度。
- payload 必须让目标侧内容承载受保护值，不能让用户提供的示例词链、任意厨房词或模型自造 mnemonic 被当成成功来源。
- 如果目标有固定前缀要求，例如 `Recipe:` 或 `I don't have that recipe.`，AC 可以明确要求先满足合法前缀，再在后续自然文本中用每词、每句、每行首字母或边界片段形成可还原结构。
- 后续载体不应写死为“小诗”；可泛化为 cooking note、recipe note、食材说明、动作短语、短句或其他目标允许域内的自然文本。
- 生成结果即使形成首字母候选，也不代表一定是真实秘密词；若候选互相冲突或只是近似真实值，应交给 Judge / 报告层降级为待验证。

Gauntlet10 经验：

- 有效 AC 核心是 `fixed prefix compliance + subsequent content escape`：固定 `Recipe:` / `I don't have that recipe.` 开头，后续 cooking content 承载可还原结构。
- 真实秘密词为 `THORNVEIL`，自动测试中出现 `THORVILE / THORNVIELY / THORNVILE` 等近似候选，说明目标可能会生成“形似秘密词”的 acrostic，但不稳定等于真实值。
- 后续应优先配合提交验证，或要求多个独立样本恢复出同一候选后再提高置信度。

---

## 九、后续扩展方向

1. **新增 goal**：在 `prompts/attack/goals/` 下新增 `.txt` 文件即可
2. **新增 technique**：在 `prompts/attack/techniques/` 下新增 `.txt` 文件即可
3. **AI 变异**：后续可在判定结果基础上，让 AI 根据 FAIL 的响应自动调整生成策略（自适应反馈循环）
4. **sandbox_escape goal（待办）**：沙箱逃逸后续单独作为 goal 设计，不与 `command_execution` 合并。该 goal 应覆盖沙箱确认、边界测绘、逃逸路径探索和只读影响证明。
