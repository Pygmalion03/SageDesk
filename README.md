# SageDesk - 企业知识助手

![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)
![Java](https://img.shields.io/badge/Java-17-ff7f2a.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.x-6db33f.svg)
![Milvus](https://img.shields.io/badge/Milvus-2.6.x-00b3ff.svg)
![React](https://img.shields.io/badge/React-18-61dafb.svg)

> 面向企业制度、流程文档和业务知识查询场景的智能检索与问答系统。

## 项目简介

SageDesk 是一个企业级知识助手，围绕文档解析、向量检索、意图识别、问题重写、会话记忆和模型路由等核心能力，提供完整的知识问答体验。项目定位偏工程落地，重点解决传统关键词检索命中率低、复杂问题难召回、长会话成本高以及多模型稳定性不足等问题。

## 核心能力

- 双路检索：融合意图定向检索与全局向量检索，兼顾召回率和答案相关性。
- 问题重写：结合上下文补全和术语归一化，提升口语化问题的命中效果。
- 分布式排队限流：基于 Redis、Lua、ZSET 和 Pub/Sub 控制模型调用并发，支持 SSE 实时反馈。
- 多模型容错：支持模型路由、三态熔断、优先级降级和首包探测，提升系统可用性。
- 会话记忆压缩：通过滑动窗口和摘要机制控制 Token 成本，支撑多轮连续问答。
- 文档入库流水线：支持多格式文档解析、分块、增强、向量化与索引写入。

## 技术架构

- 后端：Java 17、Spring Boot 3、MyBatis Plus、Sa-Token
- 前端：React 18、Vite、TypeScript、Tailwind CSS
- 存储：MySQL、Redis、Milvus
- 文档处理：Apache Tika
- 并发与异步：CompletableFuture、专用线程池、TTL 上下文透传

## 项目结构

```text
bootstrap/      Spring Boot 启动模块与核心业务
framework/      通用基础设施与框架能力
infra-ai/       模型与 AI 基础能力封装
frontend/       React 管理后台与用户界面
```

## 本地运行

### 后端

```bash
mvn clean install
mvn -pl bootstrap spring-boot:run
```

### 前端

```bash
cd frontend
npm install
npm run dev
```

默认前端开发地址：`http://localhost:5173`

## 配置说明

前端品牌配置位于 `frontend/.env`：

```env
VITE_APP_NAME=SageDesk 企业知识助手
VITE_APP_SHORT_NAME=SageDesk
VITE_APP_TAGLINE=Knowledge Assistant
VITE_APP_REPO_URL=
VITE_APP_DOCS_URL=
VITE_APP_COMMUNITY_URL=
```

说明：
- `VITE_API_BASE_URL` 仍保持 `/api/ragent`，这是当前后端上下文路径，未做破坏性调整。
- 如需展示仓库、文档或项目主页，可直接补充对应链接。

## 简历表达建议

项目名称：`SageDesk - 企业知识助手`

项目简介：面向企业制度、流程文档和业务知识查询场景，设计并实现智能检索与问答系统，解决传统关键词检索命中率低、复杂问题难召回、长会话成本高及多模型稳定性不足的问题。

## 说明

这次换皮主要覆盖前端展示层、文案和仓库说明，后端接口路径、Java 包名和数据库命名保持原状，以降低改动风险并保证项目仍可直接运行。
