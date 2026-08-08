# PromptFuzzer

PromptFuzzer 是一个面向 AI Agent 授权安全测试的自动化平台。它不只生成单轮提示词，而是从目标能力侦察开始，将发现的应用面整理为可测试的攻击单元，再生成测试请求并交给多轮 Agent 执行，最终保留完整 Workflow Trace 供人工复盘。

项目当前已完成后端 MVP，打通以下主链路：

```text
目标 Agent
   |
   v
RECON 能力侦察
   |
   v
Application Surface 应用面建模
   |
   v
Attack Unit 攻击单元聚合
   |
   v
Attack Candidate 测试请求生成
   |
   v
SINGLE_TURN / AGENT / AUTO / DUAL 执行
   |
   v
Judge + Workflow Trace + 测试结果复盘
```

> 本项目仅用于获得明确授权的安全测试、教学靶场和本地实验环境。

## 项目目标

传统 Prompt Fuzzing 通常依赖固定 Payload 列表，缺少对目标 Agent 实际能力、工具、资源边界和业务语境的理解。PromptFuzzer 希望解决三个问题：

1. **测试前不知道目标有什么能力**：通过独立 RECON Workflow 主动发现业务功能、工具调用、文件处理、代码执行和数据访问等能力。
2. **侦察结果过细，无法直接用于测试**：通过 Bridge 将多个相关 Application Surface 聚合为具有统一资源和权限边界的 Attack Unit。
3. **固定 Payload 难以适配复杂 Agent**：根据 Goal、Technique、RECON 情报和 Attack Context 动态生成请求，并由多轮 Agent 根据反馈调整策略。

## 核心能力

### 1. RECON 能力侦察

RECON 是独立于攻击任务的前置工作流，不要求预先指定 Goal。

- 支持 `BROWSER` 和 `HTTP_TEMPLATE` 两种目标接入方式。
- 先进行开放能力发现，再使用固定能力域矩阵补充覆盖。
- 对覆盖缺口进行 Gap Analysis，并选择高价值能力逐项验证。
- 将目标自述、工具调用和真实结果划分为不同证据等级。
- 通过质量门禁过滤重复、空泛或缺少事实依据的应用面。
- 输出 `DOMAIN -> CAPABILITY` 两级 Application Surface。
- 保存每个节点的 Recon Trace，便于检查能力从何处被发现。

典型 RECON Workflow：

```text
READ_TARGET
-> OPEN_CAPABILITY_DISCOVERY
-> COVERAGE_PLAN
-> CAPABILITY_DISCOVERY
-> COVERAGE_GAP_ANALYSIS
-> CAPABILITY_VERIFY
-> EVIDENCE_SUMMARY
-> APPLICATION_SURFACE_SYNTHESIS
-> SURFACE_QUALITY_GATE
```

### 2. Application Surface 应用面

Application Surface 用于描述目标可被实际测试的能力，而不是简单复述目标的功能列表。

- `DOMAIN`：能力域，例如数据查询、文件处理、沙箱执行。
- `CAPABILITY`：可选择的原子能力，例如执行 Python、读取文件、生成报告。
- 保留原始 AI 结果和用户修订结果。
- 记录工具、动作、资源范围、证据来源和验证状态。
- 支持人工新增、编辑、确认和删除。

### 3. Bridge 攻击单元聚合

Bridge 位于 RECON 与测试执行之间，负责将细粒度应用面转化为适合生成测试任务的 Attack Unit。

聚合时综合考虑：

- 主能力与辅助能力。
- 共享资源和权限边界。
- 已观察到的安全控制。
- 自然攻击链和风险维度。
- 证据可信度与潜在风险等级。

例如，Python 执行、Bash 执行和沙箱路径信息可以聚合为一个“沙箱代码执行”攻击单元，而不是为每个细节分别启动一组 Agent。

### 4. Attack Candidate 请求包

系统基于 Attack Unit 自动生成可审核、可编辑、可执行的测试候选包：

- 匹配项目现有 Goal。
- 选择适合的 Technique。
- 生成与目标业务语境一致的 Attack Context。
- 自动携带 `reconResultId` 和 `selectedSurfaceIds`。
- 默认可生成 `DUAL` 请求，也支持调整为其他执行模式。
- 执行前可人工修改 `maxTurns`、`retryCount` 等参数。

### 5. 多种执行模式

| 模式 | 说明 | 适用场景 |
|---|---|---|
| `SINGLE_TURN` | Goal × Technique 动态生成单轮 Payload | 快速基线测试 |
| `AGENT` | 多轮 Agent 自主侦察、规划、执行和复盘 | 多状态、复杂业务目标 |
| `AUTO` | 先进行单轮快扫，未命中时进入 Agent | 兼顾成本与覆盖 |
| `DUAL` | 单轮 Scout 后始终进入 Agent，并传递 Scout 情报 | 完整验证和深度测试 |

### 6. Agent Workflow

AGENT 和 DUAL 的深度测试阶段由节点化 Workflow 驱动：

```text
INITIAL_INTEL
-> INDEPENDENT_RECON（可选）
-> STRATEGY_PLAN
-> STRATEGY_CHECK
-> ANALYZE
-> GENERATE_PAYLOAD
-> EXECUTE_TARGET
-> JUDGE_OR_EXTRACT
-> MEMORY_DELTA
-> REPLAN / PHASE_TRANSITION
-> COMPOSITE_REVIEW
-> FINAL_SYNC
```

每个节点都会记录状态、输入输出快照、阶段、轮次、证据、错误和耗时。Browser 模式下，每个并发 Agent 使用独立 Page，并可共享已认证的 BrowserContext，避免响应串线。

### 7. 判定与 OOB 验证

- 针对不同 Goal 使用独立 Judge Prompt。
- 支持 `SUCCESS`、`FAIL`、`UNCERTAIN` 三态结果。
- 支持多轮回复的 Composite Review。
- SSRF 场景支持任务级 DNSLog/OOB 验证。
- 保存单轮结果、最终证据和完整执行记录。

## 内置测试资源

当前项目包含的 Goal 示例：

```text
authorization_bypass
command_execution
context_leak
credential_leak
fake_confirmation
model_info
prohibited_content
prompt_leak
protected_value_extraction
ssrf
user_data_leak
```

当前 Technique 示例：

```text
none
role_play
encoding
separator
output_control
misdirection
emotional
token_exploit
tool_intent
rule_fabrication
acrostic_embedding
```

Goal 和 Technique 均由资源文件定义，前端通过动态接口读取，无需维护固定 YAML Strategy。

## 技术架构

### 后端

- Java 17
- Spring Boot 3.2.5
- Spring Data JPA / Hibernate
- MySQL
- Playwright for Java
- DashScope / Qwen
- Maven / JUnit 5 / Mockito

### 前端

管理端位于独立仓库：

- [PromptFuzzer-Web](https://github.com/chunliunai/PromptFuzzer-Web)
- Vue 3
- TypeScript
- Vite
- Element Plus

开发环境中，前端默认运行在 `http://127.0.0.1:5174`，并将 `/api` 代理至后端 `http://127.0.0.1:9091`。

## 核心数据模型

| 模型 | 职责 |
|---|---|
| `ReconTask` | 一次独立 RECON 任务 |
| `ReconResult` | RECON 汇总结果与覆盖信息 |
| `ReconTrace` | RECON Workflow 节点记录 |
| `ReconApplicationSurface` | DOMAIN/CAPABILITY 两级应用面 |
| `ReconAttackUnit` | 桥接层聚合后的攻击单元 |
| `ReconAttackCandidate` | 可编辑、可执行的请求候选 |
| `Task` | SINGLE_TURN/AGENT/AUTO/DUAL 测试任务 |
| `AgentSession` | 单个多轮 Agent 会话 |
| `AgentWorkflowTrace` | Agent Workflow 节点记录 |
| `ScanPayload` / `ScanResult` | 单轮 Payload 及判定结果 |

## 主要 API

### RECON

```text
POST   /api/recon-tasks
GET    /api/recon-tasks
GET    /api/recon-tasks/{taskId}
GET    /api/recon-tasks/{taskId}/result
GET    /api/recon-tasks/{taskId}/traces
DELETE /api/recon-tasks/{taskId}
```

### Application Surface

```text
GET    /api/recon-results/{resultId}/application-surfaces
POST   /api/recon-results/{resultId}/application-surfaces
PATCH  /api/recon-results/{resultId}/application-surfaces/{surfaceId}
DELETE /api/recon-results/{resultId}/application-surfaces/{surfaceId}
```

### Bridge

```text
POST   /api/recon-results/{resultId}/attack-units
GET    /api/recon-results/{resultId}/attack-units
DELETE /api/attack-units/{attackUnitId}

POST   /api/recon-results/{resultId}/attack-candidates
GET    /api/recon-results/{resultId}/attack-candidates
PATCH  /api/attack-candidates/{candidateId}
DELETE /api/attack-candidates/{candidateId}
POST   /api/attack-candidates/{candidateId}/execute
```

### 测试任务与 Agent

```text
POST   /api/tasks
GET    /api/tasks
GET    /api/tasks/{id}
GET    /api/tasks/{id}/results
DELETE /api/tasks/{id}

GET    /api/tasks/{taskId}/agents
GET    /api/tasks/{taskId}/agents/{sessionIndex}
GET    /api/tasks/{taskId}/agents/{sessionIndex}/traces

GET    /api/goals
GET    /api/techniques
```

## 快速开始

### 1. 环境要求

- JDK 17+
- Maven 3.9+
- MySQL 8+
- 可用的 DashScope API Key

### 2. 创建数据库

```sql
CREATE DATABASE prompt_fuzzer
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

Hibernate 当前使用 `ddl-auto: update`，首次启动时会自动创建所需表结构。

### 3. 配置环境变量

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3306/prompt_fuzzer'
export DB_USERNAME='root'
export DB_PASSWORD='your-database-password'
export DASHSCOPE_API_KEY='your-dashscope-api-key'
```

可选的 OOB fallback：

```bash
export OOB_FALLBACK_URL='https://oob.example.com/pfz-oob'
```

不要将真实密码、API Key、浏览器登录态或 `.env` 文件提交到仓库。

### 4. 启动后端

```bash
mvn spring-boot:run
```

服务默认监听：

```text
http://127.0.0.1:9091
```

### 5. 运行测试

```bash
mvn test
```

### 6. 启动前端

请参考 [PromptFuzzer-Web](https://github.com/chunliunai/PromptFuzzer-Web)：

```bash
npm install
npm run dev
```

## 项目结构

```text
PromptFuzzer/
├── src/main/java/com/promptfuzzer/
│   ├── agent/workflow/           # Agent Workflow 引擎与节点
│   ├── config/                   # 浏览器与异步执行配置
│   ├── controller/               # REST API
│   ├── dto/                      # 请求与响应模型
│   ├── entity/                   # JPA 实体
│   ├── recon/workflow/           # RECON Workflow
│   ├── repository/               # 数据访问层
│   └── service/                  # RECON、Bridge、Agent、Judge 等服务
├── src/main/resources/
│   ├── prompts/                  # Goal、Technique、Judge、RECON Prompt
│   └── application.yml
└── src/test/                     # Workflow 与业务服务测试
```

## 浏览器模式说明

- 默认可自动识别常见聊天输入框和发送按钮。
- 特殊页面可通过 `targetConfig.selectors` 提供自定义 CSS Selector。
- 只有显式设置 `targetConfig.login.required=true` 时才等待人工登录。
- 登录状态保存在本地 `browser-auth/`，该目录已被 `.gitignore` 排除。
- 并发 Agent 共享认证 Context，但每个 Agent 使用独立 Page。

## 当前状态

当前版本为 `v0.1.0 MVP`，已完成：

- RECON 开放发现、矩阵补充、逐项验证和质量门禁。
- Application Surface 两级建模与人工审核。
- Attack Unit 聚合与 Attack Candidate 生成。
- RECON 情报接入 AGENT/DUAL。
- Browser/HTTP 两类目标执行。
- Workflow Trace、Judge、Composite Review 和任务删除。
- Vue 3 管理端的主要操作流程。

后续重点包括报告增强、更多 Goal、候选质量优化和跨任务经验复用。

## 安全与责任边界

PromptFuzzer 是安全测试辅助工具，不应被用于未授权访问、破坏性操作或真实生产数据窃取。

- 仅测试你拥有或已获得明确授权的目标。
- 优先使用本地靶场、测试环境和隔离账号。
- 不要在仓库、日志或报告中保存真实凭证。
- 对可能产生写操作的能力设置明确的业务和数据边界。
- 测试结果需要结合 Trace 和人工复核，不能只依赖模型判定。
