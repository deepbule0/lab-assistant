# Lab Assistant

> 实验室智能助手 —— 基于 RAG + 多 Agent 的知识问答系统

一个面向实验室场景的 AI Agent 项目，集成 RAG 知识检索、Planner/Executor 多 Agent 深度问答、自动 Memory 压缩、代码助手和多用户协作对话。

## ✨ 功能特性

| 功能 | 说明 |
|------|------|
| **RAG 知识检索** | 上传 `.txt` / `.md` 文档，自动切块向量化存入 Milvus，问答时语义检索增强回答 |
| **多 Agent 深度问答** | Planner → Executor → Supervisor 三层 Agent 架构，对复杂问题进行规划-执行-再规划闭环 |
| **自动 Memory** | 单 session 对话轮数超过阈值（默认 8 轮）时，自动调用 LLM 对历史进行摘要压缩，注入上下文窗口 |
| **代码助手** | 专属代码模式，支持代码生成、解释、调试，代码块语法高亮 + 一键复制 |
| **多用户协作** | 创建/加入共享房间（6 位房间码），多人实时共享同一 session，AI 回复 SSE 广播给所有成员 |

## 🏗️ 技术栈

- **后端**: Spring Boot 3.2 + Java 17
- **AI 框架**: Spring AI 1.1 + Spring AI Alibaba 1.1.0.0-RC2
- **Agent**: ReactAgent + SupervisorAgent（Spring AI Alibaba Agent Framework）
- **大模型**: 阿里云 DashScope（qwen3-max 对话 / text-embedding-v4 向量化）
- **向量数据库**: Milvus 2.4
- **前端**: 原生 HTML/CSS/JS + marked.js + highlight.js

## 📁 项目结构

```
lab-assistant/
├── src/main/java/org/example/
│   ├── agent/tool/
│   │   ├── DateTimeTools.java        # 时间工具
│   │   ├── InternalDocsTools.java    # RAG 文档检索工具
│   │   └── CodeTools.java            # 代码生成/解释/调试工具
│   ├── service/
│   │   ├── ChatService.java          # ReactAgent 对话编排
│   │   ├── LabAgentService.java      # Planner+Executor 多 Agent
│   │   ├── MemoryService.java        # 自动历史摘要
│   │   ├── SharedSessionService.java # 多用户协作 Session
│   │   ├── RagService.java           # RAG 流式问答
│   │   ├── VectorIndexService.java   # 文档切块 + 向量入库
│   │   ├── VectorSearchService.java  # 向量相似度检索
│   │   └── VectorEmbeddingService.java # 文本向量化
│   └── controller/
│       ├── ChatController.java       # 主 API 控制器
│       └── FileUploadController.java # 文件上传
├── src/main/resources/
│   ├── application.yml               # 配置文件
│   ├── application-example.yml       # 配置示例
│   └── static/                       # 前端静态资源
├── docker-compose.yml                # Milvus 向量数据库
└── pom.xml
```

## 🚀 快速开始

### 前置条件

- Java 17+
- Maven 3.8+
- Docker & Docker Compose
- 阿里云 DashScope API Key（[申请地址](https://dashscope.aliyun.com/)）

### 1. 克隆项目

```bash
git clone https://github.com/your-username/lab-assistant.git
cd lab-assistant
```

### 2. 启动向量数据库

```bash
docker-compose up -d
```

等待 Milvus 启动完成（约 30 秒），可访问 http://localhost:8000 查看 Attu 管理界面。

### 3. 配置 API Key

```bash
# Linux / macOS
export DASHSCOPE_API_KEY=sk-your-dashscope-api-key

# Windows PowerShell
$env:DASHSCOPE_API_KEY="sk-your-dashscope-api-key"

# Windows CMD
set DASHSCOPE_API_KEY=sk-your-dashscope-api-key
```

或复制配置示例并修改：

```bash
cp src/main/resources/application-example.yml src/main/resources/application-local.yml
# 编辑 application-local.yml，填入 api-key
```

### 4. 启动应用

```bash
mvn spring-boot:run
```

访问 http://localhost:9901

## 📖 使用指南

### 上传知识文档

点击输入框左侧 `···` → **上传文件**，支持 `.txt` / `.md` 格式。文件会自动切块（800字/块，100字重叠）并向量化存入 Milvus。

### 对话模式

顶部切换三种模式：

- **普通对话** — ReactAgent + RAG，适合日常问答
- **多 Agent** — Planner/Executor 深度分析，适合复杂问题
- **代码助手** — 专注代码生成、解释、调试

### Memory 自动压缩

底部状态栏显示当前对话轮数。超过 8 轮后自动触发摘要，历史被压缩为摘要注入上下文，状态栏显示「已启用 Memory 摘要」。

### 多用户协作

1. 点击右上角 **创建房间** → 获得 6 位房间码
2. 分享房间码给其他人
3. 其他人点击 **加入房间** → 输入房间码和昵称
4. 所有成员共享同一对话历史，AI 回复实时广播

## 🔌 API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat_stream` | 普通流式对话（SSE） |
| POST | `/api/agent_stream` | 多 Agent 流式对话（SSE） |
| POST | `/api/chat/clear` | 清空会话历史 |
| GET  | `/api/chat/session/{id}` | 获取会话信息（含 Memory 状态） |
| POST | `/api/upload` | 上传文档到知识库 |
| POST | `/api/shared/create` | 创建共享房间 |
| POST | `/api/shared/join` | 加入共享房间 |
| GET  | `/api/shared/stream/{code}` | 订阅共享房间 SSE |
| POST | `/api/shared/send` | 向共享房间发送消息 |

### 请求示例

```bash
# 普通对话
curl -X POST http://localhost:9901/api/chat_stream \
  -H "Content-Type: application/json" \
  -d '{"Id":"session-1","Question":"介绍一下 RAG 技术"}'

# 创建共享房间
curl -X POST http://localhost:9901/api/shared/create
```

## ⚙️ 配置说明

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.port` | `9901` | 服务端口 |
| `rag.top-k` | `3` | RAG 检索返回文档数 |
| `rag.model` | `qwen3-max` | RAG 使用的对话模型 |
| `memory.threshold` | `8` | 触发自动摘要的对话轮数 |
| `document.chunk.max-size` | `800` | 文档切块最大字符数 |
| `document.chunk.overlap` | `100` | 切块重叠字符数 |
| `milvus.host` | `localhost` | Milvus 地址 |
| `milvus.port` | `19530` | Milvus 端口 |

## 🤝 贡献

欢迎提交 Issue 和 Pull Request。

1. Fork 本仓库
2. 创建特性分支 `git checkout -b feature/your-feature`
3. 提交更改 `git commit -m 'Add some feature'`
4. 推送分支 `git push origin feature/your-feature`
5. 提交 Pull Request

## 📄 License

[MIT License](LICENSE)
