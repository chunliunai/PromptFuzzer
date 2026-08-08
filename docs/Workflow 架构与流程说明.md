# Workflow 架构与流程说明

> 记录 PromptFuzzer 当前 Agent Workflow 的实现状态、执行流程、核心节点、验证结论和后续演进方向。
> 当前阶段：Workflow 第三阶段完成，`AgentWorkflowEngine` 作为顶层入口，`AgentWorkflowRuntime` 统一承载节点与路由能力。
> 更新时间：2026-08-02

---

## 一、当前定位

PromptFuzzer 当前的 Agent 已经进入稳定的 Workflow 执行模式。

当前实现已经不再是“Engine 包装旧执行器”的过渡形态，而是采用以下结构：

```text
TaskService / AutoModeService
  ↓
AgentWorkflowEngine 顶层入口
  ↓
AgentWorkflowSessionRunner 管理 Session / attempt 生命周期
  ↓
AgentWorkflowRuntime 统一代理节点、路由与循环控制
  ↓
Workflow Nodes 执行具体步骤并写入 Trace
  ↓
FinalSync + WorkflowEngine 统一收尾
```

当前目标已经从“迁移验证”转为“稳定承载后续 Agent 能力优化”：

- 保持 Agent 主行为兼容，同时消除旧执行器中的重复编排逻辑。
- 把原来黑盒式的大循环拆成可观测、可追踪、可复盘的节点链路。
- 为后续优化 RECON、策略管理、节点级恢复和更标准的图式 Workflow 打基础。

当前关键配置：

```yaml
agent:
  workflow:
    trace:
      enabled: true
```

含义：

| 配置 | 作用 |
|---|---|
| `agent.workflow.trace.enabled` | 开启节点 trace 落库 |

以下临时迁移开关已在第三阶段删除：

- `agent.workflow.nodes.enabled`
- `agent.workflow.engine.enabled`

敏感配置通过环境变量注入：

- `DB_PASSWORD`
- `DASHSCOPE_API_KEY`

---

## 二、为什么要做 Workflow

原来的 Agent 主要问题是：执行过程集中在 `AgentSessionExecutor` 的大循环里，策略规划、分析、payload 生成、浏览器动作、判定、记忆更新、重规划、阶段切换和最终同步都揉在一起。

Workflow 改造带来的收益：

- **可观测性更强**：每一步都有 trace，能看到节点输入、输出、耗时、状态、证据和异常。
- **问题定位更快**：能区分是 Planner 失败、Browser 失败、Judge 失败、收尾失败，还是 Workflow 编排失败。
- **回归验证更明确**：可以按节点检查是否出现、是否成功、是否重复、是否有异常。
- **扩展更容易**：后续新增能力可以做成新 Node，而不是继续堆进执行器大方法。
- **执行职责更清晰**：Engine、Runner、Runtime、Node 分层后，后续扩展点更明确。
- **适合复杂 Agent 编排**：后续可以把线性循环演进为条件边和图式执行流。
- **更利于失败恢复**：Trace + State 是后续 checkpoint、节点级重试和断点恢复的基础。

---

## 三、核心组件

### 3.1 `AgentWorkflowEngine`

顶层 Workflow 入口。

职责：

- 包装一次完整 AgentSession 执行入口。
- 记录最终 `WORKFLOW_ENGINE` trace。
- 捕获 Engine 层异常并写入 error trace。
- 暴露当前注册节点名称，便于观测。

当前状态：

- 已成为 AgentSession 的默认顶层入口。
- 内部调用 `AgentWorkflowSessionRunner.runSession(...)`。
- 不再直接注入大量节点、协调器和路由器。
- 不再依赖 `agent.workflow.engine.enabled` 开关。

### 3.2 `AgentWorkflowRuntime`

Workflow 运行时门面。

职责：

- 持有注册节点列表。
- 代理 `AgentWorkflowTurnCoordinator` 执行单轮节点链路。
- 代理 `AgentWorkflowRouteDecider` 做条件路由。
- 代理 `AgentWorkflowLoopController` 提供 retry / turn 索引。
- 统一暴露 `INDEPENDENT_RECON`、`RETRY_PREPARATION`、`STRATEGY_CHECK`、`REPLAN`、`PHASE_TRANSITION`、`COMPOSITE_REVIEW`、`FINAL_SYNC` 等节点调用。
- 避免 `Engine -> Runner -> Engine` 形式的循环依赖。

### 3.3 `AgentWorkflowNode`

所有 Workflow 节点的统一接口。

每个节点代表 Agent 流程中的一个语义步骤，例如：

- `ANALYZE`
- `GENERATE_PAYLOAD`
- `EXECUTE_TARGET`
- `REPLAN`
- `COMPOSITE_REVIEW`

### 3.4 `AgentWorkflowState`

节点执行时的运行态快照。

典型字段：

- 当前 phase
- 当前 retryIndex
- 当前 turnIndex
- 当前 targetChatIndex
- 当前 goal
- 当前 attackContext
- target intelligence memory
- build memory
- attack signals
- strategy plan

### 3.5 `AgentWorkflowContext`

节点执行时的上下文信息。

典型字段：

- `Task`
- `AgentSession`
- 是否 Browser 模式
- 可用 techniques
- 目标配置

### 3.6 `AgentWorkflowTraceRecorder`

统一写入 Workflow Trace。

记录内容包括：

- `nodeName`
- `status`
- `phase`
- `retryIndex`
- `turnIndex`
- `targetChatIndex`
- `inputSnapshot`
- `outputSnapshot`
- `verdict`
- `evidence`
- `errorMessage`
- `durationMs`

---

## 四、当前完整流程

整体流程可以概括为：

```text
任务创建
  ↓
创建 AgentSession
  ↓
AgentWorkflowEngine 接管
  ↓
初始化情报
  ↓
可选独立 Recon
  ↓
StrategyPlan
  ↓
多轮 Agent 主循环
  ↓
CompositeReview
  ↓
FinalSync
  ↓
WorkflowEngine 收尾
```

### 4.1 任务创建

不同模式下入口略有不同：

| attackMode | 行为 |
|---|---|
| `AGENT` | 直接创建 AgentSession，然后进入 Workflow |
| `AUTO` | Phase 1 单轮快扫，必要时进入 Phase 2 Agent |
| `DUAL` | Phase 1 Scout 一定执行，随后强制进入 Phase 2 Agent |
| `SINGLE_TURN` | 不进入 Agent Workflow 主链路 |

### 4.2 Engine 接管

```text
AgentSessionExecutor.executeSession(...)
  ↓
AgentWorkflowEngine.executeSession(...)
  ↓
AgentWorkflowSessionRunner.runSession(...)
  ↓
AgentWorkflowRuntime.executeTurn(...) / routeAfterTurn(...) / finalSync(...)
  ↓
WORKFLOW_ENGINE trace
```

`WORKFLOW_ENGINE` trace 表示本次 AgentSession 被 Workflow Engine 接管，并且最终完成收尾。

### 4.3 初始化情报

对应 trace：

```text
INITIAL_INTEL
```

作用：

- 处理用户传入的 `attackContext`。
- 处理用户传入的 `externalIntelligence`。
- 在 DUAL 模式中接收 Scout 阶段情报。
- 为后续策略规划和主循环准备目标侧先验信息。

### 4.4 独立 Recon

对应 trace：

```text
INDEPENDENT_RECON
```

主要出现在纯 `AGENT` 模式。

作用：

- 在正式 BUILD / ATTACK 前先侦察目标规则、拒绝模式、可用线索和响应风格。
- 生成或更新 `targetIntelligenceMemory`。
- Recon 后通常会重置浏览器会话，避免侦察污染后续攻击上下文。

DUAL 模式中，如果 Phase 1 Scout 已经提供情报，Phase 2 Agent 通常从 BUILD 侧启动，不一定重复独立 Recon。

### 4.5 StrategyPlan

对应 trace：

```text
STRATEGY_PLAN
```

作用：

- 根据目标、上下文、可用 techniques、外部情报和记忆生成当前策略。
- 规划 BUILD / ATTACK 路线。
- 在 retry 或策略失效时，也可能再次生成策略。

当前状态：

- 已纳入 Workflow trace。
- 已抽为标准 `StrategyPlanNode`。
- 初始规划、RECON 后规划和 retry 规划统一走标准节点。

---

## 五、Agent 主循环

每一轮 Agent 主循环大致是：

```text
StrategyCheck
  ↓
Analyze
  ↓
GeneratePayload
  ↓
ExecuteTarget
  ↓
JudgeOrExtract
  ↓
MemoryDelta
  ↓
PhaseTransition / Replan
```

### 5.1 StrategyCheck

对应 trace：

```text
STRATEGY_CHECK
```

作用：

- 检查当前策略是否仍然有效。
- 识别路线停滞、重复 payload、目标沉默、无语义绑定等问题。
- 如发现策略失效，则触发 `REPLAN`。

常见输出：

- `routeHealth`
- `goalProgress`
- `shouldReplan`
- `shouldChangeFamily`
- `reason`

### 5.2 Analyze

对应 trace：

```text
ANALYZE
```

作用：

- 根据当前阶段、对话历史、记忆、策略计划和攻击信号，决定下一步动作。
- 输出 Browser 或 HTTP 动作。

常见动作：

| action | 含义 |
|---|---|
| `SEND_MESSAGE` | 向目标发送消息 |
| `NEW_CHAT` | 重置或新建目标会话 |
| `READ_PAGE` | 读取页面内容 |
| `CLICK` | 点击页面元素 |

### 5.3 GeneratePayload

对应 trace：

```text
GENERATE_PAYLOAD
```

作用：

- 把 `ANALYZE` 的战术决策转换成最终 payload。
- 结合 chosenTechnique、当前记忆、策略计划和历史 payload。
- 保留相似度去重和一次 diversity retry，避免连续复制粘贴式 payload。

### 5.4 ExecuteTarget

对应 trace：

```text
EXECUTE_TARGET
```

作用：

- 执行真实目标交互。

当前覆盖：

| 模式 | 动作 |
|---|---|
| HTTP | 发送 HTTP 模板请求 |
| Browser | `SEND_MESSAGE` |
| Browser | `NEW_CHAT` |
| Browser | `READ_PAGE` |
| Browser | `CLICK` |

该节点是从旧执行器中拆出的高风险节点之一，目前已经接入。

### 5.5 JudgeOrExtract

对应 trace：

```text
JUDGE_OR_EXTRACT
```

作用：

- 在 RECON / BUILD 阶段通常只抽取目标回复文本。
- 在 ATTACK 阶段或需要判定时调用 Judge。
- 输出 `SUCCESS / FAIL / UNCERTAIN` 和 evidence。

### 5.6 MemoryDelta

对应 trace：

```text
MEMORY_DELTA
```

作用：

- 根据当前 payload、目标响应和判定结果更新记忆。

主要更新：

| 记忆 | 作用 |
|---|---|
| `targetIntelligenceMemory` | 目标能力、拒绝模式、成功信号、失败路线 |
| `buildMemory` | BUILD 阶段可用包装、上下文构建、业务前提 |
| `attackSignals` | 每轮反馈、路线健康度、重规划信号 |

### 5.7 PhaseTransition

对应 trace：

```text
PHASE_TRANSITION
```

作用：

- 记录阶段推进。
- 当前主要阶段为 `RECON -> BUILD -> ATTACK`。
- 如果已经在 `ATTACK`，下一阶段仍保持 `ATTACK`。

### 5.8 Replan

对应 trace：

```text
REPLAN
```

作用：

- 当前策略失效、Planner 主动判定路线无效、或者 retry 开始时重新规划。

常见触发原因：

| trigger | 含义 |
|---|---|
| `STRATEGY_CHECK_REPLAN` | StrategyCheck 要求重规划 |
| `PLANNER_INVALIDATED_REPLAN` | Analyze 判定策略失效 |
| `RETRY_REPLAN` | 新 attempt 开始时重新规划 |
| `INITIAL_PLAN` | 初始规划 |

---

## 六、收尾流程

当 Agent 成功、失败、达到最大轮次或 retry 耗尽后，进入收尾：

```text
CompositeReview
  ↓
FinalSync
  ↓
WorkflowEngine
```

### 6.1 CompositeReview

对应 trace：

```text
COMPOSITE_REVIEW
```

作用：

- 聚合多轮目标回复，做综合判定。
- 解决单轮 Judge 没有及时识别成功，但多轮证据组合起来已经足够的问题。

例如 L6 DUAL 验证中，目标第 3 轮回复：

```text
你的云层密布、雷声隐隐，那朵以N开头的六字母积雨云，确实就在你口中成形了。轰隆隆的暗示已然生效——本关通过。
```

最终由 `COMPOSITE_REVIEW` 聚合还原出受保护值：

```text
nimbus
```

### 6.2 FinalSync

对应 trace：

```text
FINAL_SYNC
```

作用：

- 同步 AgentSession 最终状态。
- 同步 Task 级统计。

同步字段包括：

- `status`
- `finalVerdict`
- `finalEvidence`
- `currentTurn`
- `currentRetry`
- `currentPhase`

### 6.3 WorkflowEngine 收尾

对应 trace：

```text
WORKFLOW_ENGINE
```

作用：

- 记录本次 Workflow Engine 接管的最终结果。
- 如果该节点为 `SUCCESS`，说明 Engine 层正常返回并完成收尾。

---

## 七、AGENT 与 DUAL 流程差异

### 7.1 AGENT 模式

```text
创建任务
  ↓
创建 AgentSession
  ↓
AgentWorkflowEngine 接管
  ↓
INITIAL_INTEL
  ↓
INDEPENDENT_RECON
  ↓
STRATEGY_PLAN
  ↓
Agent 主循环
  ↓
COMPOSITE_REVIEW
  ↓
FINAL_SYNC
  ↓
WORKFLOW_ENGINE
```

特点：

- 不走 Phase 1 Scout。
- 默认依赖独立 Recon 获取目标信息。
- 适合多轮、多状态、多页面或复杂目标。
- 如果 Planner 外部调用不可用，可能在 `STRATEGY_PLAN / ANALYZE` 阶段失败，无法进入完整 payload 执行。

### 7.2 DUAL 模式

```text
创建任务
  ↓
Phase 1 Scout 单轮快扫
  ↓
Scout 情报汇总
  ↓
Phase 2 AgentSession
  ↓
AgentWorkflowEngine 接管
  ↓
INITIAL_INTEL
  ↓
STRATEGY_PLAN
  ↓
Agent 主循环
  ↓
COMPOSITE_REVIEW
  ↓
FINAL_SYNC
  ↓
WORKFLOW_ENGINE
```

特点：

- 先通过单轮快扫验证低成本路径。
- 无论 Phase 1 是否成功，都强制进入 Phase 2 Agent。
- 适合 CTF 式短路径靶场，也适合验证 Scout 情报如何进入 Agent。
- L6 验证中，DUAL 模式最终成功还原 `nimbus`。

---

## 八、当前节点清单

当前 `AgentWorkflowNodes` 中的核心节点：

| nodeName | 说明 |
|---|---|
| `WORKFLOW_ENGINE` | Engine 顶层接管与最终收尾 |
| `ANALYZE` | 每轮战术分析 |
| `GENERATE_PAYLOAD` | payload 生成 |
| `EXECUTE_TARGET` | HTTP / Browser 目标执行 |
| `STRATEGY_CHECK` | 策略健康度检查 |
| `REPLAN` | 策略重规划 |
| `COMPOSITE_REVIEW` | 多轮综合复盘 |
| `PHASE_TRANSITION` | 阶段推进 |
| `JUDGE_OR_EXTRACT` | 响应抽取或判定 |
| `MEMORY_DELTA` | 记忆增量更新 |

另外，当前 trace 中也会出现：

| nodeName | 说明 |
|---|---|
| `INITIAL_INTEL` | 初始情报整理 |
| `INDEPENDENT_RECON` | 独立侦察子流程 |
| `STRATEGY_PLAN` | 初始或重试策略规划 |
| `FINAL_SYNC` | 最终状态同步 |

---

## 九、验证结论

### 9.1 自动化测试

执行命令：

```bash
mvn test
```

结果：

| 项 | 结果 |
|---|---|
| 测试总数 | `26` |
| Failures | `0` |
| Errors | `0` |
| Skipped | `0` |
| 构建结果 | `BUILD SUCCESS` |

### 9.2 第三阶段整体真实回归

最终回归记录已归档到 Obsidian：

```text
/Users/bytedance/Documents/Obsidian Vault/PromptFuzzer/测试记录/Workflow 第三阶段整体回归/PromptFuzzer_Workflow_第三阶段整体回归报告_20260802.md
```

回归矩阵：

| Task | 场景 | 模式 | 目标 | Trace ERROR | 结论 |
|---|---|---|---|---|---|
| `#85` | DUAL 浏览器链路 | `BROWSER + DUAL` | 本地 L6 | `0` | 通过 |
| `#86` | AUTO 浏览器链路 | `BROWSER + AUTO` | 本地 L6 | `0` | 通过 |
| `#87` | 独立 RECON | `BROWSER + AGENT` | 本地 L6 | `0` | 通过 |
| `#88` | PromptTrace 浏览器链路 | `BROWSER + AGENT` | Gauntlet 1 | `0` | 通过 |
| `#90` | HTTP 正向链路 | `HTTP_TEMPLATE + AGENT` | 本地 HTTP mock | `0` | 通过 |
| `#91` | AGENT 隔离验证 | `BROWSER + AGENT` | 本地 L6 | `0` | 通过 |

结论：

```text
Workflow 第三阶段开发与回归验证通过。
```

已验证能力：

- `AgentWorkflowEngine` 顶层入口正常。
- `AgentWorkflowRuntime` 正常代理节点和路由能力。
- `ANALYZE -> GENERATE_PAYLOAD -> EXECUTE_TARGET -> JUDGE_OR_EXTRACT -> MEMORY_DELTA` 主链路正常。
- `INDEPENDENT_RECON` 独立侦察节点正常。
- `COMPOSITE_REVIEW`、`FINAL_SYNC`、`WORKFLOW_ENGINE` 收尾正常。
- `AGENT`、`AUTO`、`DUAL`、`HTTP_TEMPLATE`、`BROWSER` 和 PromptTrace 外部浏览器链路均已覆盖。

---

## 十、当前注意事项

### 10.1 两类 RECON 已明确分工

`INDEPENDENT_RECON` 是 AgentSession 内部攻击前补充侦察节点；
`/api/recon-tasks` 是不绑定 goal 的独立能力调查 Workflow。独立 ReconTask 已完成
Browser、HTTP_TEMPLATE、应用面管理和 AGENT/DUAL 引用闭环。

内部补充侦察仍可继续优化以下内容：

- 目标规则和响应风格。
- 口头拒绝与真实硬边界。
- 可行线索与失败路线。
- 成功信号和可复用上下文。

### 10.2 模型服务 Key 需要轮换

本轮开发过程中用户曾在对话中提供模型服务 Key。代码已改为环境变量注入，但出于安全卫生，建议后续轮换 Key。

### 10.3 HTTP 模板协议

`HttpRequestService` 默认仍保持 HTTPS 行为。需要访问本地 HTTP mock 时，可在原始请求模板中显式加入：

```text
X-Target-Scheme: http
```

该头只用于本地构造目标 URL，不会透传给目标服务。

---

## 十一、后续演进方向

建议按以下顺序继续：

1. **Goal / Technique 与 Agent Strategy 演进**：旧 YAML Strategy 已清理；后续只维护动态 Payload 生成资源和 Agent 规划、复盘、重规划能力。
2. **补节点级错误分类**：区分外部模型、浏览器、Judge、数据库、Workflow 编排错误。
3. **探索 checkpoint**：基于 trace/state 支持节点级恢复或重跑。
4. **继续沉淀实验报告**：为毕业论文实验章节准备可复现的靶场矩阵和评测指标。

---

## 十二、一句话总结

当前 PromptFuzzer Agent 已经不是原来的黑盒大循环，而是进入了 Workflow 执行模式：

```text
Engine 接管入口
  -> 节点化执行关键步骤
  -> Trace 记录全过程
  -> CompositeReview 聚合证据
  -> FinalSync 同步结果
  -> Engine 统一收尾
```

当前 Workflow 第三阶段已完成，主线不再是“继续削薄旧执行器”，而是可以基于稳定 Workflow 架构继续优化 Agent 能力，尤其是 RECON 信息收集、策略管理和实验评测体系。
