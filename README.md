# MCP Database Tools Server（MCP 基座）

MCP（Model Context Protocol）数据库工具基座服务：MCP 客户端（AI 智能体 / LLM 应用）
通过 MCP 端点调用本服务，服务再查询数据库。

- **技术栈**：Java 17 · Spring Boot 3.3.13 · solon-ai-mcp 3.9.5（MCP 端点由 Solon 容器管理，Spring Boot 提供 Web 容器）
- **数据库**：`database-spring-boot-starter`（Gaarason）+ HikariCP 连接池
- **构建**：Maven

## 快速开始

```bash
# 编译
mvn -q clean compile

# 启动（默认 pass 模式，鉴权空置放行）
mvn spring-boot:run

# 启动（deny 模式，联调灰度封禁）
mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Dmcp.auth.mode=deny"

# 运行单元测试（不依赖真实数据库，开发期即可跑）
mvn test
```

启动成功后：
- Tomcat 监听 `8080`，MCP 端点 `/mcp/database-tools`（Streamable Stateless，集群友好）
- 默认 `mcp.auth.mode=pass`（鉴权空置放行）；`mcp.db.write-enabled=false`（写类操作关闭）；
  `mcp.db.ddl-enabled=false`（DDL 关闭）

## 项目结构

```
com.chaoxing.mcpserver
├── auth                    调用方鉴权框架（caller-side auth）
├── mcp                     MCP 端点 + 双容器装配（Solon/Spring）
├── db                      只读 SQL 查询 + 安全防护（JDBC 直查逃生舱）
├── config.gaarason         Gaarason 查询封装（数据源通道 + 强类型查询工具，可扩展）
└── common                  统一响应（Result）+ 统一异常（DbException + ErrorCode + DbExceptionMapper）
```

## 文档索引

各模块的详细介绍、实现链条、配置契约、冒烟测试见 `docs/` 目录：

| 文档 | 内容 |
|---|---|
| [docs/overview.md](docs/overview.md) | 架构总览 + 模块清单 + 配置总览 + 已知约束 |
| [docs/auth.md](docs/auth.md) | Auth 鉴权框架：职责边界、请求链、配置契约、行为矩阵、甲方逻辑落地步骤 |
| [docs/mcp-endpoint.md](docs/mcp-endpoint.md) | MCP 端点 + 双容器架构：装配链、单端点多工具设计决策、客户端接入示例 |
| [docs/database-tools.md](docs/database-tools.md) | 数据库工具 + Gaarason 查询封装：7 个查询工具清单、ReadonlySqlGuard 防护、GaarasonQueryService/GaarasonQueryTools、冒烟测试 |
| [docs/unified-response-exception.md](docs/unified-response-exception.md) | 统一响应包装 + 统一异常处理：Result、DbException、ErrorCode 错误码速查、映射规则、脱敏约定 |
| [docs/connection-pool.md](docs/connection-pool.md) | 连接池统一配置：连接链路、包结构、配置契约、Gaarason 已知坑 |

另有 [ARCHITECTURE.md](ARCHITECTURE.md)（双容器架构详细说明 + 请求链全图 + 落地顺序）。

## 下一步

- 甲方数据库连接信息到位后，替换 `application.yml` 里 `spring.datasource.*`
  的占位值（url/username/password），**池参数无需改动**（已收敛到 `spring.datasource.hikari.*`）
- 甲方加密/签名校验逻辑到位后，新增 `TokenVerifier` 实现类 + 切 `mcp.auth.mode=custom`
  （步骤见 [docs/auth.md](docs/auth.md) 第五节）
- 当前基座**纯查询**（写类/DDL 工具已移除）；需要数据写操作或 schema 管理时，
  需另加确认/白名单/审计机制后另行设计（见 [docs/database-tools.md](docs/database-tools.md)）
- 可扩展新查询类型：在 `GaarasonQueryTools` 加强类型方法 + 端点加 `@ToolMapping`，
  底层统一走 Gaarason 数据源通道（见 [docs/database-tools.md](docs/database-tools.md)）
