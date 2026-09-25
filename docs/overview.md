# MCP 基座 — 架构总览

## 整体架构（Spring Boot + Solon 双容器混跑）

```
HTTP 请求
  │
  ▼
Spring Tomcat（spring-boot-starter-web，端口 8080）
  │
  └─ SolonServletFilter（McpServerConfig 里 @Bean FilterRegistrationBean 注册 /mcp/*、/sse/*）
        将请求转发给 Solon 容器
  │
  ▼
Solon AppContext（McpServerConfig @PostConstruct 中 Solon.start() 启动，enableScanning(false)）
  │
  ├─ McpPlugin（SPI 插件，solon-ai-mcp）
  │
  └─ FilterChain（含 McpAuthFilter）→ 路由匹配 → MCP 端点
        McpAuthFilter（Solon Filter，由 app.router().filter() 显式注册）
        先于 MCP 协议帧解析执行：token 校验不通过直接写 401/403 并终止链路
  │
  ▼
McpServerEndpointProvider（McpServerConfig.springCom2Endpoint() 手动构建）
  收集 Spring 容器里 @McpServerEndpoint 注解的 IMcpServerEndpoint Bean
  │
  ▼
MCP 端点（@McpServerEndpoint + @ToolMapping 工具方法，单一端点挂多个强类型查询工具）
  │
  ▼
工具执行层（db / config.gaarason 包）
  ├─ ReadonlySqlGuard（只读 SQL 防护，不合法 → code=2001）
  ├─ GaarasonQueryTools → GaarasonQueryService（Gaarason 数据源通道，取连接失败 → code=1001/1002）
  └─ SQLException → DbExceptionMapper.map() → DbException（统一业务异常，code=10xx）
        业务层只 catch DbException，不读 SQLState
  │
  ▼
Result<T>（common 包，统一响应包装）→ 序列化为 JSON 返回 MCP 客户端
```

> 详细说明见各模块文档：
> - [MCP 端点与双容器架构](../ARCHITECTURE.md)
> - [Auth 鉴权框架](auth.md)
> - [数据库工具与统一响应/异常](database-tools.md)
> - [连接池统一配置](connection-pool.md)

## 模块清单

| 模块 | 包 | 职责 | 文档 |
|---|---|---|---|
| Auth 鉴权 | `com.chaoxing.mcpserver.auth` | 调用方鉴权（caller-side auth），token 校验先于 MCP 协议帧解析 | [auth.md](auth.md) |
| MCP 端点 | `com.chaoxing.mcpserver.mcp` | Solon/Spring 双容器装配、单一 MCP 端点挂多个强类型工具 | [mcp-endpoint.md](mcp-endpoint.md) |
| 数据库工具 | `com.chaoxing.mcpserver.db` | 只读 SQL 查询 + 安全防护（JDBC 直查逃生舱） | [database-tools.md](database-tools.md) |
| Gaarason 查询封装 | `com.chaoxing.mcpserver.config.gaarason` | Gaarason 数据源通道查询（参数化 + 强类型查询工具，可扩展） | [database-tools.md](database-tools.md) |
| 统一响应/异常 | `com.chaoxing.mcpserver.common` | Result 响应 + DbException + 错误码 | [unified-response-exception.md](unified-response-exception.md) |
| 连接池配置 | `com.chaoxing.mcpserver.config.pool` | HikariCP 池参数统一收敛 + 唯一取连接门面 | [connection-pool.md](connection-pool.md) |

## 配置总览

```yaml
# application.yml
mcp:
  auth:
    mode: pass                # pass（默认）| deny（灰度封禁）| custom（甲方逻辑落地后切这里）
    custom-verifier-bean: ""  # mode=custom 时，指定 TokenVerifier Bean 名
  db:
    readonly-enabled: true    # 只读查询工具总开关
    max-rows: 200             # 单次查询最大返回行数
    statement-timeout-seconds: 30

spring:
  datasource:
    url: jdbc:mysql://...     # 甲方连接信息到位后替换
    driver-class-name: com.mysql.cj.jdbc.Driver
    username: ...
    password: ...
    hikari:                   # 连接池参数统一收敛（业务代码不直接读）
      pool-name: McpDatabasePool
      maximum-pool-size: 10
      minimum-idle: 0
      connection-timeout: 5000
      idle-timeout: 300000
      max-lifetime: 1800000
      connection-test-query: "SELECT 1"
      initialization-fail-timeout: -1   # 占位池不阻塞启动
```

运行时可用 JVM 参数覆盖：`-Dmcp.auth.mode=deny` / `-Dmcp.db.max-rows=5`。

## 已知约束

- 端点路径固定在 `/mcp/*`、`/sse/*`（SolonServletFilter URL 模式）
- 端点 channel = STREAMABLE_STATELESS（集群友好，无 session）
- Solon 扫描禁用（`enableScanning(false)`），MCP 端点必须通过 Spring Bean 手动构建
- `McpAuthFilter` 的 `TokenVerifier`/`TokenExtractor` 是 Spring Bean，由 `McpServerConfig`
  构造器注入后手动 `new McpAuthFilter(...)` 传入 Solon FilterChain
- 连接池：Spring Boot 自动配置 `HikariDataSource`（读 `spring.datasource.hikari.*`）；
  Gaarason 的 `GaarasonDataSource`（`@Primary`）包装它做路由；`ConnectionProvider`
  注入 Gaarason 路由数据源，对外暴露唯一 `getConnection()`
- DataSource 当前为占位配置（`127.0.0.1:3306/mcp_tools`），甲方连接信息到位后替换
  `spring.datasource.*`（url/username/password），池参数无需改动
- 只读 SQL 工具是白名单（SELECT/WITH）+ 关键字黑名单防护；**写类/DDL 工具已移除**（基座纯查询，
  需另加确认/白名单/审计机制后另行设计）
