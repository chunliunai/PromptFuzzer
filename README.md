# PromptFuzzer

PromptFuzzer 是一个面向授权安全测试场景的 Agent 自动化测试平台后端。

核心流程：

```text
RECON
-> ApplicationSurface
-> AttackUnit
-> AttackCandidate
-> AGENT / DUAL
-> Workflow Trace
```

## 技术栈

- Java 17
- Spring Boot 3.2
- Spring Data JPA
- MySQL
- Playwright for Java
- DashScope / Qwen

## 本地运行

准备 MySQL 数据库：

```sql
CREATE DATABASE prompt_fuzzer
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

设置环境变量：

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

启动服务：

```bash
mvn spring-boot:run
```

后端默认监听 `http://127.0.0.1:9091`。

## 测试

```bash
mvn test
```

## 前端

管理端位于独立仓库 `PromptFuzzer-Web`，开发环境通过 Vite 将 `/api`
代理到本服务的 `9091` 端口。

## 文档

架构、Workflow、RECON 和 Payload 设计说明位于 [`docs/`](docs/)。

## 安全说明

本项目仅用于获得明确授权的安全测试、教学靶场和本地实验环境。
请勿对未授权目标使用。
