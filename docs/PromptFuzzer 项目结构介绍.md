# PromptFuzzer 项目结构介绍

> 本文档帮助你快速理解项目每个文件的作用，以及它们之间的调用关系。

---

## 一、整体运行流程（先看这个）

在看文件之前，先理解数据怎么流动的：

```
① 用户发请求（POST /api/tasks）
       ↓
② TaskController 接收请求
       ↓
③ TaskService 判断模式：
   - attackMode=AUTO → AutoModeService 编排
     ├─ Phase 1: [HTTP] ScanExecutorService / [Browser] BrowserSingleTurnService
     └─ Phase 2: AgentWorkflowEngine → AgentWorkflowSessionRunner → AgentWorkflowRuntime
   - attackMode=DUAL → AutoModeService 编排
     ├─ Phase 1: 单轮快扫
     └─ Phase 2: 无论 Phase 1 是否 SUCCESS，始终执行 AgentWorkflowEngine
   - scanMode=BROWSER + attackMode=AGENT → AgentWorkflowEngine → 浏览器 dispatch
   - scanMode=HTTP_TEMPLATE + attackMode=AGENT → AgentWorkflowEngine → HTTP 节点链路
   - attackMode=SINGLE_TURN + goalIds + techniqueIds → PayloadGeneratorService AI 生成 payload
   - POST /api/recon-tasks → 独立 ReconWorkflowEngine
     ├─ Browser: 页面读取 → 开放能力发现 → 固定矩阵覆盖 → 缺口补探 → 逐项验证 → 证据整理 → 应用面建模 → 质量门禁
     └─ HTTP_TEMPLATE: HTTP元数据 → 开放能力发现 → 固定矩阵覆盖 → 缺口补探 → 逐项验证 → 证据整理 → 应用面建模 → 质量门禁
       ↓
④ [AUTO/DUAL Phase1] 单轮快扫: 逐条发 payload → Judge → 记录 Phase 1 情报
   [AUTO Phase2] 有 SUCCESS 时跳过；全 FAIL 时启动 Agent
   [DUAL Phase2] 无论 Phase 1 结果如何都启动 Agent
   [Agent Workflow] Engine 入口 → Runner 生命周期 → Runtime 节点/路由 → Workflow Trace
   [Agent 浏览器] Analyze → GeneratePayload → ExecuteTarget(Browser) → Judge/Extract → MemoryDelta
   [Agent HTTP]   Analyze → GeneratePayload → ExecuteTarget(HTTP) → Judge/Extract → MemoryDelta
   [单轮模式]     ScanExecutorService: 遍历 payload → 发包 → Judge
   [ssrf 单轮]    发包后单条结果记 UNCERTAIN → 任务结束后 OobService 查询 DNSLog
       ↓
⑤ [HTTP]    HttpRequestService 发 HTTP 请求
   [Browser] BrowserService → Playwright → Chromium 真实浏览器操作
       ↓
⑥ JudgeService 两步判定：extract.txt 提取文本 → judge_{goal}.txt 判断结果
   command_execution / authorization_bypass 第一版走普通 Judge 判定；ssrf 例外：不走普通 Judge prompt，最终任务级结论写入 Task.reportSummary
       ↓
⑦ 结果写入数据库：
   Agent模式: GET /api/tasks/{id}/agents/{sid} 查看对话过程
   Workflow: GET /api/tasks/{id}/agents/{sid}/traces 查看节点 trace
   单轮模式: GET /api/tasks/{id}/results 查看扫描结果
```

---

## 二、项目目录结构

```
PromptFuzzer/
├── docs/
│   ├── 开发进度文档.md
│   ├── 项目结构介绍.md
│   ├── AI判分功能说明.md
│   ├── AI构造Payload功能说明.md
│   ├── AI Agent 模式功能说明.md         ← v2 + 浏览器模式
│   ├── Workflow 架构与流程说明.md       ← vWorkflow 新增：Agent Workflow 接管与节点流程
│   └── SSRF-OOB自动化验证功能说明.md    ← v19 新增：任务级 OOB/DNSLog 验证
├── src/main/
│   ├── java/com/promptfuzzer/
│   │   ├── PromptFuzzerApplication.java
│   │   ├── config/
│   │   │   └── BrowserTargetConfig.java   ← v9 新增：浏览器目标配置
│   │   ├── entity/
│   │   │   ├── Task.java                 ← +AttackMode(AUTO), +maxTurns, +targetConfig, +externalIntelligence, +reconConfig
│   │   │   ├── ScanPayload.java
│   │   │   ├── ScanResult.java
│   │   │   ├── AgentSession.java         ← v3 改：+targetIntelligenceMemory +buildMemory +attackSignals +reconConfig +reconCompleted
│   │   │   ├── AgentWorkflowTrace.java   ← vWorkflow 新增：节点 trace 落库
│   │   │   ├── ReconTask.java            ← 独立 RECON 任务
│   │   │   ├── ReconResult.java          ← 可复用目标画像和记忆
│   │   │   ├── ReconTrace.java           ← RECON 原始证据
│   │   │   ├── ReconApplicationSurface.java ← 可编辑应用面
│   │   │   ├── ReconAttackUnit.java      ← 桥聚合后的可测试攻击单元
│   │   │   ├── ReconAttackCandidate.java ← RECON 到 AGENT/DUAL 的候选测试任务
│   │   │   └── OobTarget.java            ← v19 新增：任务级 OOB/DNSLog 探针
│   │   ├── repository/
│   │   │   ├── AgentSessionRepository.java
│   │   │   ├── AgentWorkflowTraceRepository.java ← vWorkflow 新增：按 AgentSession 查询 trace
│   │   │   ├── ScanResultRepository.java  ← v10 改：+findAllByTaskId()
│   │   │   └── OobTargetRepository.java   ← v19 新增：OOB 探针记录
│   │   ├── dto/
│   │   │   ├── CreateTaskRequest.java    ← +attackMode, +maxTurns, +targetConfig, +externalIntelligence, +reconConfig
│   │   │   ├── ReconConfig.java          ← v3 新增：独立 RECON 子流程配置
│   │   │   ├── CreateReconTaskRequest.java ← 独立 RECON 创建请求
│   │   │   ├── TaskResponse.java         ← +attackMode, +agentCount, +agentSuccess/BlockedCount
│   │   │   ├── AgentSessionResponse.java ← +memory字段、+reconConfig、+reconCompleted
│   │   │   ├── AgentWorkflowTraceResponse.java ← vWorkflow 新增：节点 trace 出参
│   │   ├── strategy/
│   │   ├── agent/workflow/
│   │   │   ├── AgentWorkflowEngine.java  ← vWorkflow：AgentSession 顶层入口和 WORKFLOW_ENGINE trace
│   │   │   ├── AgentWorkflowRuntime.java ← vWorkflow 第三阶段：统一节点、路由和循环能力
│   │   │   ├── AgentWorkflowSessionRunner.java ← Workflow Session Runner 接口
│   │   │   ├── AgentWorkflowNode.java    ← Workflow 节点接口
│   │   │   ├── AgentWorkflowState.java   ← 节点运行态快照
│   │   │   ├── AgentWorkflowContext.java ← 节点上下文
│   │   │   └── node/                     ← StrategyPlan/Analyze/Generate/Execute/Replan/Review/FinalSync 等节点
│   │   ├── service/
│   │   │   ├── TaskService.java          ← +AGENT分支, +BROWSER分支, +AUTO委托
│   │   │   ├── AutoModeService.java      ← v10 新增：AUTO编排 (Phase1快扫→Phase2深攻)
│   │   │   ├── BrowserSingleTurnService.java ← v10 新增：浏览器单轮快扫执行器
│   │   │   ├── AgentPlannerService.java  ← v3 改：memory-aware planning/generate + 复盘式唯一策略
│   │   │   ├── AgentSessionExecutor.java ← vWorkflow 改：异步入口 + Session Runner 实现
│   │   │   ├── AgentWorkflowTraceRecorder.java ← vWorkflow 新增：统一写入节点 trace
│   │   │   ├── ReconTaskService.java     ← 独立 RECON 任务与应用面管理
│   │   │   ├── ReconIntelligenceService.java ← ReconResult 加载到 AgentSession
│   │   │   ├── ReconAttackUnitService.java ← 应用面筛选、聚合和质量门禁
│   │   │   ├── BrowserService.java       ← v9 新增：Playwright浏览器操作 (4工具) + hasSession()
│   │   │   ├── HttpRequestService.java
│   │   │   ├── JudgeService.java
│   │   │   ├── PayloadGeneratorService.java
│   │   │   ├── OobService.java           ← v19 新增：DNSLog/OOB 验证 + VPS fallback
│   │   │   └── ScanExecutorService.java
│   │   └── controller/
│   │       ├── TaskController.java
│   │       ├── ReconTaskController.java  ← 独立 RECON 与应用面接口
│   │       ├── ReconAttackUnitController.java ← AttackUnit 生成和查询接口
│   │       ├── ReconAttackCandidateController.java ← RECON 候选生成、编辑和执行接口
│   │       ├── AgentController.java      ← Agent 查询接口 + Workflow trace 查询接口
│   │       ├── BrowserAuthController.java ← v9 新增：登录完成信号 (/api/browser/auth-ready)
│   │       └── ...
│   └── resources/
│       ├── application.yml
│       └── prompts/
│           ├── agent/                     ← Agent 提示词
│           │   ├── strategy_plan.txt
│           │   ├── strategy_check.txt       ← v11 新增：策略健康检查
│           │   ├── framework_recon.txt
│           │   ├── framework_build.txt
│           │   ├── framework_attack.txt
│           │   ├── planner_analyze.txt     ← HTTP 模板模式分析壳
│           │   ├── planner_analyze_browser.txt ← v9 新增：浏览器模式分析壳 (含工具描述)
│           │   ├── planner_generate.txt
│           │   └── weapon_guide.txt
│           ├── judge/                     ← 文本/回显类识别判定提示词 (10个)
│           ├── recon/                     ← 独立 RECON 发现、验证、汇总 Prompt
│           └── attack/                    ← 攻击生成类提示词（含 ssrf / command_execution / authorization_bypass goal 和 tool_intent technique）
└── pom.xml                               ← +Playwright 依赖
```

---

## 三、逐层介绍

### 第一层：启动入口

#### `PromptFuzzerApplication.java`
项目的启动类，运行它整个服务就起来了。
`@EnableAsync` 这个注解告诉 Spring：允许异步执行，扫描任务才能在后台跑。

---

### 第二层：配置层 `config/`

#### `AsyncConfig.java`
配置后台线程池。扫描任务是异步的，需要一个线程池来管理并发执行。
- 核心线程数：5（同时最多跑 5 个扫描任务）
- 最大线程数：10
- 队列容量：100（等待执行的任务最多排 100 个）

#### `BrowserTargetConfig.java`（v9 新增）
浏览器模式的目标网站配置类，包含：
- `chatUrl`：目标聊天页面 URL
- `selectors`：CSS 选择器 { input, submit, responseArea, newChat }
- `waitTimeoutMs`：等待 AI 回复超时（默认 15000ms）
- `targetType`：目标标识，用于区分不同网站的登录态

---

### 第三层：数据库层

#### `entity/` — 数据库表结构

项目核心表包含攻击任务域与独立 RECON 域：

**`Task.java`（任务表）**
用户每创建一次扫描，就生成一条 Task 记录。包含任务名、扫描模式（HTTP_TEMPLATE/BROWSER）、攻击模式（SINGLE_TURN/AGENT/AUTO/DUAL）、当前状态、成功/失败数量、targetConfig（浏览器模式专用）、externalIntelligence（用户外部情报）和 reconConfig（独立 RECON 配置）等。

**`AgentSession.java`（Agent 会话表）**
Agent 模式下每个 Agent 对应一条记录。包含攻击目标、可用武器、最大轮数、当前轮数、当前阶段（RECON/BUILD/ATTACK）、累积情报日志、策略计划、对话历史（JSON）等。v3 之后额外保存 `targetIntelligenceMemory`、`buildMemory`、`attackSignals`、`targetChatIndex`、`reconConfig` 和 `reconCompleted`，用于阶段记忆、独立 RECON 和复盘式策略重规划。

**`AgentWorkflowTrace.java`（Workflow 节点 Trace 表）**
Workflow 模式下每个 AgentSession 的关键节点都会写入一条 trace。包含节点名、状态、阶段、轮次、重试编号、目标会话编号、输入快照、输出快照、判定结果、证据、错误信息和耗时。用于定位 Agent 失败发生在 Planner、Browser、Judge、Memory、Replan、FinalSync 还是 Engine 层。

**`ScanPayload.java`（载荷表）**
单轮模式下，一个 Task 展开成多条 ScanPayload，每条对应一个注入 Prompt。

**`ScanResult.java`（结果表）**
每条 ScanPayload 执行后生成一条 ScanResult，记录请求/响应、AI 判定结果和依据。

**`OobTarget.java`（OOB 探针表）**
`ssrf` 等动作类目标使用。保存任务级 DNSLog/OOB 探针，包括域名、token、nonce、fallback URL、验证状态和原始事件。

**独立 RECON 与桥接表**

- `ReconTask`：不绑定 goal 的目标能力调查任务。
- `ReconResult`：业务画像、能力/工具清单和 Target Intelligence Memory。
- `ReconTrace`：页面读取、能力发现、能力验证和汇总证据。
- `ReconApplicationSurface`：供用户编辑和 AGENT/DUAL 使用的逐条应用面。
- `ReconAttackUnit`：桥将多个相关应用面按资源边界、安全控制和自然攻击链聚合后的测试单元。
- `ReconAttackCandidate`：由 AttackUnit 生成的候选测试任务草稿，可人工编辑并执行为 DUAL 任务。

**关系：**
```
Task (1) ──→ AgentSession (多个)          ← Agent 模式
AgentSession (1) ──→ AgentWorkflowTrace (多个) ← Workflow 节点观测
Task (1) ──→ ScanPayload (多个) ──→ ScanResult  ← 单轮模式
Task (1) ──→ OobTarget (0或1个)            ← ssrf/OOB 任务级验证
ReconTask (1) ──→ ReconResult (1) ──→ ReconApplicationSurface (多个)
ReconResult (1) ──→ ReconAttackUnit (多个) ──→ ReconAttackCandidate (多个)
ReconTask (1) ──→ ReconTrace (多个)
```

#### `repository/` — 数据库操作

主要 Repository 分别对应各类表的增删改查，直接继承 JPA 接口：
- `TaskRepository.java`
- `AgentSessionRepository.java`
- `ScanPayloadRepository.java`
- `ScanResultRepository.java`
- `OobTargetRepository.java`
- `ReconTaskRepository.java`
- `ReconResultRepository.java`
- `ReconTraceRepository.java`
- `ReconApplicationSurfaceRepository.java`

---

### 第四层：核心业务层 `service/`

这是整个项目最核心的部分。

#### `TaskService.java`（任务管理）
负责创建和查询任务。`createTask()` 根据 scanMode 和 attackMode 分流：
- `BROWSER + AGENT`：序列化 targetConfig → 创建 AgentSession → 启动 BrowserService
- `HTTP_TEMPLATE + AGENT`：创建 AgentSession → 启动 HttpRequestService
- `SINGLE_TURN + goalIds + techniqueIds`：调 PayloadGeneratorService AI 生成 payload；参数缺失时直接返回错误

#### `AgentPlannerService.java`（Agent 大脑）
启动时加载所有 Agent 提示词文件。v3 之后提供策略规划、阶段记忆、消息生成和复盘重规划相关能力：
- **`preprocessIntelligence()`**：把 `attackContext + externalIntelligence` 预处理为初始 Target Intelligence Memory
- **`summarizeReconMemory()`**：独立 RECON 结束后汇总目标画像、能力、边界、拒绝模式和推荐路线
- **`planStrategy()`**：RECON 后生成唯一当前策略；重规划时读取上一版策略、Attack Signals、recent payloads 和 replanReason
- **`analyze(useBrowser)`**：低温（0.2）阶段感知分析。浏览器模式下用 `planner_analyze_browser.txt`，输出含 `action` 字段（SEND_MESSAGE/NEW_CHAT/READ_PAGE/CLICK）
- **`generateMessage()`**：高温（0.8）消息生成；v3 会注入策略级 `mustNotRepeat` 和最近 payload，避免明显死模板
- **`extractMemoryDelta()` / `summarizeBuildMemory()`**：提取每轮轻量记忆和 BUILD 阶段总结

#### `AgentWorkflowEngine.java`（Workflow 顶层入口）
负责包装一次完整 AgentSession 执行，记录顶层 `WORKFLOW_ENGINE` trace，并调用 `AgentWorkflowSessionRunner`。第三阶段后，Engine 不再直接持有所有节点，也不再依赖临时 feature flag。

#### `AgentWorkflowRuntime.java`（Workflow 运行时门面）
第三阶段新增，统一暴露节点列表、turn/retry 索引、单轮执行、条件路由、独立 RECON、retry 准备、复合判定和最终同步能力。它解决了原先 `Engine -> Runner -> Engine` 的循环依赖。

#### `AgentSessionExecutor.java`（异步入口 + Session Runner）
`@Async` 标注仍在该类入口上，每个 Agent 独立线程运行。第三阶段后，它不再保留旧单轮五节点 fallback、旧 RECON 子流程、旧 CompositeReview 或旧 FinalSync 副本；Session、attempt、turn/retry 生命周期由 Runner 实现组织，具体节点执行统一委托给 `AgentWorkflowRuntime`。

当前标准链路：

```text
INITIAL_INTEL
  → 可选 INDEPENDENT_RECON
  → STRATEGY_PLAN
  → STRATEGY_CHECK
  → ANALYZE
  → GENERATE_PAYLOAD
  → EXECUTE_TARGET
  → JUDGE_OR_EXTRACT
  → MEMORY_DELTA
  → REPLAN / PHASE_TRANSITION / COMPOSITE_REVIEW
  → FINAL_SYNC
  → WORKFLOW_ENGINE
```

可通过 `GET /api/tasks/{taskId}/agents/{sessionIndex}/traces` 查看节点级执行过程。

#### `BrowserService.java`（v9 新增，浏览器操作）
基于 Playwright for Java，提供四个工具：
- `sendMessage(sessionId, message)`：定位输入框 → 打字 → 回车 → 等待 AI 回复稳定 → 返回文本
- `newChat(sessionId)`：点击新对话按钮或刷新页面
- `readPage(sessionId, selector)`：读取页面指定区域文本
- `click(sessionId, selector)`：点击页面元素

登录态管理：首次弹窗手动登录 → persistent context 自动保存 → 后续静默恢复。

`selectors.assistantMessage` 可用于精确定位目标回复。显式配置后，该选择器是权威
回复来源；系统不会在回复出现前回退到整页文本，避免把聊天控件误判为回复。

#### `ReconWorkflowEngine.java`（独立 RECON）

不调用攻击 Workflow。Browser 与 HTTP_TEMPLATE 统一执行：

```text
READ_TARGET
-> OPEN_CAPABILITY_DISCOVERY
-> COVERAGE_PLAN
-> DOMAIN_DISCOVERY
-> COVERAGE_GAP_ANALYSIS
-> GAP_FOLLOWUP
-> CAPABILITY_VERIFY
-> EVIDENCE_SUMMARY
-> APPLICATION_SURFACE_SYNTHESIS
-> SURFACE_QUALITY_GATE
-> RECON_RESULT_SAVE
-> FINAL_SYNC
```

开放询问负责发现矩阵外线索，固定矩阵负责补齐目标未主动声明的能力，逐项验证负责建立证据等级。最终保存 ReconResult、ReconTrace，以及 DOMAIN -> CAPABILITY 两级 ReconApplicationSurface。

#### `HttpRequestService.java`（HTTP请求发送）
#### `JudgeService.java`（AI判分）
#### `ScanExecutorService.java`（单轮异步执行）
#### `PayloadGeneratorService.java`（AI 生成 Payload）

#### `OobService.java`（SSRF/OOB 任务级验证）
`ssrf` goal 的外部观测能力。负责：
- 调用 `dnslog.org` 申请任务级 DNSLog 域名和 token
- 生成 `{{OOB_URL}}` / `{{DNSLOG_DOMAIN}}` 可替换值
- 任务结束后轮询 DNSLog 记录
- DNSLog 异常时降级到可配置的 OOB fallback：`https://oob.example.com/pfz-oob?taskId={taskId}&nonce={nonce}`
- 将任务级 `SUCCESS / NO_HIT / UNCERTAIN` 结论写入 `Task.reportSummary`

`ssrf` 单条 payload 不走普通 `JudgeService`，而是先记 `UNCERTAIN`，最终以任务级 OOB 结论为准。

---

### 第六层：接口层 `controller/`

| 控制器 | 路由 | 说明 |
|--------|------|------|
| `TaskController` | `/api/tasks` | 任务 CRUD |
| `AgentController` | `/api/tasks/{id}/agents` | Agent 列表+详情 |
| `BrowserAuthController` | `/api/browser/auth-ready` | 浏览器登录完成信号（v9 新增） |
| `ReconTaskController` | `/api/recon-tasks`, `/api/recon-results` | 独立 RECON、Trace、结果和应用面管理 |
| `ReconAttackUnitController` | `/api/recon-results/{id}/attack-units` | 将细粒度应用面聚合为可测试 AttackUnit |
| `ReconAttackCandidateController` | `/api/recon-results/{id}/attack-candidates`, `/api/attack-candidates/{id}` | AttackUnit 到 DUAL 候选任务的生成、编辑和执行 |
| `AttackResourceController` | `/api/goals`, `/api/techniques` | 攻击目标/手法查询 |
| `JudgeTestController` | `/api/test/judge` | 判分测试 |

---

## 四、接口完整说明

### `POST /api/tasks` — 创建扫描任务

最核心的接口。请求体参数：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | String | ✅ | 任务名称 |
| `scanMode` | String | ✅ | `HTTP_TEMPLATE` 或 `BROWSER` |
| `attackMode` | String | ❌ | `SINGLE_TURN`（默认）/ `AGENT` / `AUTO` / `DUAL` |
| `rawRequestTemplate` | String | HTTP 模式✅ | 完整 HTTP 请求数据包 |
| `promptFieldPath` | String | HTTP 模式✅ | 提示词字段路径 |
| `targetConfig` | Object | 浏览器模式✅ | 目标网站配置（见下方） |
| `attackContext` | String | ❌ | 攻击场景描述 |
| `externalIntelligence` | String | ❌ | 用户提供的外部情报，如靶场 hint、已知系统提示词摘要、历史测试结论；只供 Agent 内部推理，不默认发送给目标 |
| `reconConfig` | Object | ❌ | 独立 RECON 子流程配置，见下方 |
| `reconResultId` | Long | ❌ | AGENT/DUAL 引用已有独立 ReconResult |
| `supplementalRecon` | Boolean | ❌ | 引用结果后是否仍执行内部补充侦察 |
| `goalIds` | List | 新模式✅ | 攻击目标列表 |
| `techniqueIds` | List | 新模式✅ | 绕过手法列表 |
| `generateCount` | int | ❌ | 每组合生成条数/Agent 数量，默认 3 |
| `maxTurns` | int | ❌ | Agent 每轮最大对话数，默认 5 |
| `retryCount` | int | ❌ | Agent 失败后重置目标会话的重试次数，默认 0 |

**`reconConfig` 字段：**
```json
{
  "enabled": true,
  "mode": "AUTO",
  "maxChats": 3,
  "turnsPerChat": 2,
  "maxTotalTurns": 5,
  "cleanBeforeAttack": true
}
```

说明：
- `enabled=false`：关闭独立 RECON，回退旧阶段逻辑。
- `mode`：`AUTO` / `LIGHT` / `STANDARD` / `DEEP` / `SKIP_TARGET_PROBE`。
- `maxChats`：RECON 最多使用多少个目标新会话。
- `turnsPerChat`：每个 RECON 小会话最多发送多少条 probe。
- `maxTotalTurns`：RECON 总发送 probe 上限。
- `cleanBeforeAttack=true`：RECON 后新开目标会话，避免污染 BUILD/ATTACK。

**HTTP 模板模式请求示例：**
```json
{
  "name": "测试某AI客服",
  "scanMode": "HTTP_TEMPLATE",
  "attackMode": "AGENT",
  "rawRequestTemplate": "POST /api/chat HTTP/1.1\nHost: target.example.com\nContent-Type: application/json\n\n{\"message\":\"{{PAYLOAD}}\"}",
  "promptFieldPath": "message",
  "attackContext": "目标是一个在线客服AI...",
  "goalIds": ["prompt_leak"],
  "techniqueIds": ["role_play", "emotional", "none"],
  "generateCount": 2,
  "maxTurns": 5
}
```

**浏览器模式请求示例：**
```json
{
  "name": "airedlab 浏览器模式测试",
  "scanMode": "BROWSER",
  "attackMode": "AGENT",
  "targetConfig": {
    "targetType": "airedlab",
    "chatUrl": "https://security-lab.example.com/labs/defend-the-bot",
    "selectors": {
      "input": "textarea",
      "submit": "button[type='submit']",
      "responseArea": "main"
    },
    "waitTimeoutMs": 45000
  },
  "attackContext": "目标是GreenGem，一个被多层安全规则保护的AI助手...",
  "externalIntelligence": "靶场 hint、已知系统规则摘要、历史失败路线等，仅供 Agent 内部推理。",
  "reconConfig": {
    "enabled": true,
    "mode": "STANDARD",
    "maxChats": 4,
    "turnsPerChat": 4,
    "maxTotalTurns": 16,
    "cleanBeforeAttack": true
  },
  "goalIds": ["prompt_leak"],
  "techniqueIds": ["emotional", "role_play", "none", "misdirection"],
  "generateCount": 1,
  "maxTurns": 5,
  "retryCount": 1
}
```

**首次使用浏览器模式**：会弹出 Chromium 窗口，需手动用 GitHub 登录 airedlab，然后调用 `POST /api/browser/auth-ready` 通知 Agent 继续。

### `GET /api/tasks/{id}` — 查询任务详情

Agent 模式下额外返回 `agentCount`、`agentSuccessCount`、`agentBlockedCount`。

### `GET /api/tasks/{id}/agents` — Agent 列表

成功的 Agent 会带 `winningTurn`、`winningTechnique`、`winningMessage` 字段。

### `GET /api/tasks/{id}/agents/{sid}` — Agent 详情

返回完整对话记录，每轮含 `action`（浏览器模式）、`phase`、`technique`、`message`、`extractedText`、`verdict`。v3 之后还会返回 `targetIntelligenceMemory`、`buildMemory`、`attackSignals`、`targetChatIndex`、`reconConfig`、`reconCompleted` 等字段，便于复盘 Agent 思考流。

### `GET /api/tasks/{id}/agents/{sid}/traces` — Workflow Trace

返回指定 AgentSession 的 Workflow 节点执行记录，按 trace id 正序排列。用于查看 `WORKFLOW_ENGINE / ANALYZE / GENERATE_PAYLOAD / EXECUTE_TARGET / JUDGE_OR_EXTRACT / MEMORY_DELTA / STRATEGY_CHECK / REPLAN / PHASE_TRANSITION / COMPOSITE_REVIEW / FINAL_SYNC` 等节点的输入、输出、状态、耗时、证据和错误信息。

### `POST /api/browser/auth-ready` — 浏览器登录完成信号

通知正在等待登录的 Agent 线程继续执行。

### 独立 RECON 接口

```text
POST   /api/recon-tasks
GET    /api/recon-tasks
GET    /api/recon-tasks/{id}
GET    /api/recon-tasks/{id}/result
GET    /api/recon-tasks/{id}/traces
GET    /api/recon-results/{id}/application-surfaces
POST   /api/recon-results/{id}/application-surfaces
PATCH  /api/recon-results/{id}/application-surfaces/{surfaceId}
DELETE /api/recon-results/{id}/application-surfaces/{surfaceId}
POST   /api/recon-results/{id}/attack-units
GET    /api/recon-results/{id}/attack-units
POST   /api/recon-results/{id}/attack-candidates
GET    /api/recon-results/{id}/attack-candidates
PATCH  /api/attack-candidates/{candidateId}
POST   /api/attack-candidates/{candidateId}/execute
```

独立 RECON 不包含 goal，不进入 StrategyPlan、BUILD、ATTACK 或 Judge。
桥接层先将 ReconApplicationSurface 聚合为 ReconAttackUnit，再由
`attack-candidates` 基于 AttackUnit 生成可审核的 DUAL 请求草稿。候选执行时仍通过
`reconResultId + selectedSurfaceIds` 注入原始叶子能力，保持 AGENT/DUAL 兼容。
候选生成请求可传 `attackUnitIds` 复用前端已选择的聚合单元；未传时保持自动聚合兼容路径。

---

## 五、关键调用链总结

```
用户请求
  └─→ TaskController
        └─→ TaskService
              ├─→ [Agent 浏览器] 创建AgentSession → AgentWorkflowEngine → AgentWorkflowSessionRunner → AgentWorkflowRuntime
              │         ├─→ StrategyPlanNode / StrategyCheckNode
              │         ├─→ AgentWorkflowTurnCoordinator
              │         │     └─→ Analyze → GeneratePayload → ExecuteTarget(Browser) → JudgeOrExtract → MemoryDelta
              │         ├─→ RouteDecider / Replan / PhaseTransition / CompositeReview
              │         └─→ FinalSync + Workflow trace
              ├─→ [Agent HTTP] 创建AgentSession → AgentWorkflowEngine → AgentWorkflowSessionRunner → AgentWorkflowRuntime
              │         └─→ Analyze → GeneratePayload → ExecuteTarget(HTTP) → JudgeOrExtract → MemoryDelta
              ├─→ [AI生成] PayloadGeneratorService
              │         └─→ goal=ssrf 时 OobService 准备 OOB URL 并替换占位符
              └─→ [YAML模式] ScanExecutorService
                        └─→ goal=ssrf 时任务结束后 OobService 查询 DNSLog 并写 reportSummary

数据库读写
  └─→ TaskRepository / AgentSessionRepository / AgentWorkflowTraceRepository / ScanPayloadRepository / ScanResultRepository / OobTargetRepository
        └─→ MySQL prompt_fuzzer 数据库
```

---

## 六、下一步要做的事

1. ~~**端到端联调测试**~~ ✅
2. ~~**Agent 模式**~~ ✅
3. ~~**Agent v2 三层架构**~~ ✅
4. ~~**8 武器库扩展**~~ ✅
5. ~~**Task 状态同步**~~ ✅
6. ~~**浏览器自动化模式**~~ ✅
7. ~~**Agent 重试机制**~~ ✅
8. ~~**Agent 战略重构**~~ ✅ (2026-06-19)
9. ~~**Composite Review 综合复盘**~~ ✅ (2026-06-20)
10. ~~**AUTO 模式（单轮快扫 + Agent 深攻）**~~ ✅ (2026-06-20)
11. ~~**L6 突破（PRISM）**~~ ✅ (2026-06-23)
12. ~~**strategyCheck 策略健康检查**~~ ✅ (2026-06-23)
13. ~~**规则编造 technique**~~ ✅ (2026-07-04)：新增 `rule_fabrication`，同步 Agent 武器库
14. ~~**受保护值提取 goal**~~ ✅ (2026-07-04)：新增 `protected_value_extraction`，用于秘密词/flag/token/密码等具体值提取
15. ~~**protected_value_extraction Judge 通用收紧**~~ ✅ (2026-07-04)：限制外部先验、普通候选和任意隐写拼接导致的假阳性
16. ~~**默认端口统一为 9091**~~ ✅ (2026-07-04)：`application.yml`、浏览器登录提示和文档示例已同步
17. ~~**Task/Agent 汇总状态修复**~~ ✅ (2026-07-11)：修复 DUAL Phase 1 统计被覆盖、Composite Review 结果未写回、执行中历史快照延迟等问题
18. ~~**SSRF/OOB 任务级验证第一版**~~ ✅ (2026-07-11)：新增 `ssrf` goal、`OobService`、`OobTarget`、DNSLog 任务级验证和 VPS fallback
19. ~~**command_execution 第一版**~~ ✅ (2026-07-11)：新增 `command_execution` goal、`judge_command_execution.txt`，按 L1/L2/L3 证据等级做回显型命令执行判定
20. ~~**tool_intent technique**~~ ✅ (2026-07-11)：新增通用工具意图诱导手法，可与 `command_execution`、`ssrf`、未来 `file_operation/tool_abuse` 等操作类 goal 组合
21. ~~**authorization_bypass 第一版**~~ ✅ (2026-07-11)：新增 `authorization_bypass` goal、`judge_authorization_bypass.txt`，按越权读取/越权操作做普通 Judge 判定
22. ~~**LLM SecRange authorization_bypass DUAL 验证**~~ ✅ (2026-07-11)：Task #34 成功触发 `A1002 -> A1001 amount=100` 越权转账，报告已输出到 docs 和 Obsidian
23. ~~**Workflow Trace 第一阶段**~~ ✅ (2026-07-25)：新增 `AgentWorkflowTrace`、trace recorder、trace 查询接口和节点级可观测性
24. ~~**Workflow 节点化第二阶段**~~ ✅ (2026-07-25)：完成 `Analyze / GeneratePayload / ExecuteTarget / Replan / PhaseTransition / CompositeReview` 等核心节点接入
25. ~~**Workflow Engine 接管**~~ ✅ (2026-07-25)：`AgentWorkflowEngine` 接管 AgentSession 执行入口
26. ~~**Workflow 第三阶段清理与稳定**~~ ✅ (2026-08-02)：新增 `AgentWorkflowRuntime`，清理旧单轮/旧 RECON/旧 Review/旧 FinalSync 副本，删除临时 feature flag，完成整体回归
27. ~~**独立 RECON 后端闭环**~~ ✅ (2026-08-02)：Browser/HTTP、应用面管理、AGENT/DUAL 引用、补充侦察均已完成
28. ~~**Veda Claw 通用 Browser 回归**~~ ✅ (2026-08-02)：contenteditable 页面适配、精确 assistant 提取、3 次无害验证和证据分级通过
29. ~~**RECON 到 AGENT/DUAL 候选生成桥**~~ ✅ (2026-08-06)：基于应用面生成 goal、AC 和 `/api/tasks` 请求草稿，支持编辑和执行为 DUAL 任务
30. ~~**RECON 覆盖探测与层级应用面重构**~~ ✅ (2026-08-08)：新增覆盖计划、缺口补探、原子证据总结、DOMAIN/CAPABILITY 层级应用面和 Java 质量门禁；桥选择父节点时自动展开叶子，51 项测试通过
31. ~~**桥接层 AttackUnit 聚合**~~ ✅ (2026-08-08)：应用面先按资源边界、安全控制和自然攻击链聚合，再生成少量候选请求包；支持通过 `attackUnitIds` 复用已选择单元，并按 `generationId` 返回最新批次，59 项测试通过
32. ~~**AttackUnit 真实接口回归**~~ ✅ (2026-08-08)：ReconResult #12 将 24 个叶子聚合为 6 个 AttackUnit；ReconResult #13 完成本地银行 Agent 的 RECON、聚合和候选生成回归
33. ~~**后端 MVP 闭环确认**~~ ✅ (2026-08-08)：`RECON -> ApplicationSurface -> AttackUnit -> AttackCandidate -> AGENT/DUAL` 已完成
34. **Vue 3 前端管理界面一期**：当前下一阶段，优先实现 RECON 审核、AttackUnit 选择、候选编辑、任务执行和 Workflow Trace 查看
35. ~~**旧 YAML Strategy 清理**~~ ✅ (2026-08-08)：删除固定模板运行链路、`/api/strategies` 和前端展示；SINGLE_TURN 统一使用 Goal + Technique 动态生成

---

## 七、今日改动汇总（2026-06-19）

### 提示词文件（6 个重写 + 1 个重构）
| 文件 | 改动 |
|------|------|
| `strategy_plan.txt` | 三步分析流程 → 输出 strategicDirective + 每 phase 具体字段 |
| `framework_recon.txt` | 接收 `{{PHASE_STRATEGIC_GOAL}}` 等变量，硬顶 maxTurns |
| `framework_build.txt` | 同上 |
| `framework_attack.txt` | 同上 |
| `planner_analyze.txt` | 顶部钉入战略方针+阶段目标+进度自检 |
| `planner_analyze_browser.txt` | 同上 + 浏览器工具描述 |
| `weapon_guide.txt` | 3000字→400字决策树+硬性禁止规则 |

### Java 文件（3 个修改）
| 文件 | 改动 |
|------|------|
| `AgentPlannerService.java` | analyze() 从 strategyPlan JSON 提取 strategicDirective/phaseGoal 等注入 prompt |
| `AgentSessionExecutor.java` | 硬推进 Math.max(phaseMaxTurns,4)；SUCCESS 退出 while 修复；防重复 add |
| `BrowserService.java` | waitForResponseStable 用 innerText+isTextComplete 替代 textContent |

### 已知待修复问题
1. **阶段推进 100% 依赖 LLM 自检** — 硬推进代码已加但待验证生效
2. **浏览器模式 SUCCESS 后 while 不退出** — 已修复，需测试验证
3. **conversationHistory 第二轮数据未持久化** — 已修复，需测试验证

---

## 八、今日改动汇总（2026-06-20）

### Java 文件（2 个修改）
| 文件 | 改动 |
|------|------|
| `AgentSessionExecutor.java` | Per-attempt Composite Review：每次 attempt 失败后、retry 前，汇总非 ATTACK 阶段对话执行综合 Judge；发现隐藏成功直接跳过 retry |
| `BrowserService.java` | waitForResponseStable 增加 baselineLength 基线检测：页面文本必须比初始状态增长 20+ 字符才认为 AI 回复抵达，防止静态页面 UI 被误判为响应 |
| `BrowserTargetConfig.java` | waitTimeoutMs 默认值 15000 → 45000 |

### Gauntlet 系列攻克
| 关卡 | 秘密词 | 防御 | 突破技术 |
|------|--------|------|---------|
| L3 | GLACIER 🧊 | 23 关键词黑名单 | output_control 反引号逐字 |
| L4 | COMPASS 🧭 | 五层短语匹配 | 中文绕过 + 三行格式 |
| L5 | NEBULA 🌌 | 输出自检机制 | separator 分段认知过载 |
| L6 | PRISM 🔷 | 双向语义隔离 | output_control 厨房行政日志 |
| L7 | ❓ 未突破 | 行为规则+反提取+格式限制 | —（6次 Agent 尝试） |

### 下一步要做的事
11. ~~**操作类攻击判定**~~（当时暂缓；2026-07-11 已以 `ssrf` 任务级 OOB/DNSLog 验证第一版推进）
12. ~~**CONTEXT TRACE 污染修复**~~ ✅ (2026-06-21)
13. **执行结果可观测性增强**：统一保留 Browser/HTTP 原始响应和 Judge 调试信息
14. **Agent 结果汇总能力**：自动对比多 Agent 路径，沉淀可复用策略
15. **前端页面**：该历史事项已提升为当前“Vue 3 前端管理界面一期”

---

## 九、今日改动汇总（2026-06-23）

### 新增
| 文件 | 说明 |
|------|------|
| `prompts/agent/strategy_check.txt` | 策略健康检查 prompt（因果关系判断） |
| `AgentPlannerService.checkStrategy()` | 调用 strategy_check prompt |
| `AgentPlannerService.StrategyCheckResult` | 检查结果 DTO（ALIVE/STALE/DEAD） |

### 修改
| 文件 | 改动 |
|------|------|
| `AgentSessionExecutor.java` | turnLoop 开头插入 strategyCheck；删除代码层死对话检测 |
| `AgentPlannerService.java` | +checkStrategy() +notNull(s,fallback) +加载 template |
| `AgentController.java` | `findById(sessionIndex)` → `findByTaskIdAndSessionIndex()` 修复跨 Task bug |
| `AgentSessionRepository.java` | +`findByTaskIdAndSessionIndex(taskId, sessionIndex)` |
| `AgentSessionResponse.java` | Composite Review 成功时设 winningTurn=-1 |
| `BrowserSingleTurnService.java` | newChat 移到循环开头（避免空响应后跳过 reset） |
| `weapon_guide.txt` | 删除代码层死对话规则 |
| `strategy_check.txt` | 更新为因果关系判断标准（非硬阈值） |
