# MCP 端点 + 双容器架构（mcp 包）

## 一、架构背景

本项目采用 **Spring Boot + Solon 双容器混跑**：
- Spring Boot 提供 Web 容器（Tomcat，端口 8080）+ 组件管理
- Solon 容器管理 MCP 端点（`solon-ai-mcp`），MCP 协议帧解析、工具注册、调用

**为什么混跑**：`solon-ai-mcp` 的 MCP 端点注解体系（`@McpServerEndpoint` / `@ToolMapping`）
属于 Solon 生态；但业务侧已有 Spring Boot 组件（数据源、配置、鉴权）。
通过 `SolonServletFilter` 把 `/mcp/*`、`/sse/*` 路径桥接到 Solon 容器，
其他路径仍走 Spring MVC，两个容器共存于同一进程。

## 二、端点装配链（McpServerConfig）

```
McpServerConfig（@Configuration）
  │
  ├─ @PostConstruct start()
  │     ├─ ToolSchemaUtil.addBodyDetector / addParamResolver
  │     │     （支持 Spring @RequestBody / @RequestParam 注解作为 MCP 工具参数描述）
  │     ├─ System.setProperty("server.contextPath", ...)
  │     └─ Solon.start(McpServerConfig.class, new String[]{}, app -> {
  │           app.enableScanning(false);                       // Spring 已管理组件，禁用 Solon 扫描
  │           app.router().filter(new McpAuthFilter(tokenVerifier, tokenExtractor));
  │        });                                                // 显式注册鉴权过滤器
  │
  ├─ springCom2Endpoint()
  │     遍历 Spring 容器里带 @McpServerEndpoint 的 IMcpServerEndpoint Bean：
  │     └─ McpServerEndpointProvider.builder().from(cls, anno).build()
  │          .addTool(new MethodToolProvider(...))            // 注册所有 @ToolMapping 方法
  │          .addResource(new MethodResourceProvider(...))
  │          .addPrompt(new MethodPromptProvider(...))
  │          .postStart();
  │
  ├─ @Bean FilterRegistrationBean<SolonServletFilter> mcpServerFilter()
  │     注册 /mcp/*、/sse/* URL pattern，桥接到 Solon 容器
  │
  └─ @PreDestroy stop()
        Solon.stopBlock(false, Solon.cfg().stopDelay())
```

## 三、包结构

```
com.chaoxing.mcpserver.mcp
├── McpServerConfig.java               Solon.start() + app.router().filter(McpAuthFilter)
│                                       + 手动构建端点 provider + 注册 SolonServletFilter
├── McpDatabaseToolsEndpoint.java       MCP 端点（/mcp/database-tools），单一端点挂 9 个强类型工具
└── McpDatabasePropertiesConfiguration.java  绑定 mcp.db.* + 兜底 ObjectMapper
```

## 四、端点设计：单一端点 + 多个强类型工具

**核心决策**：端点仅一个（`/mcp/database-tools`），但端点下注册多个
`@ToolMapping` 工具方法，每个操作独立强类型参数。

**为什么不用工厂模式**：
- 工厂模式（1 个工具名 + `op` 参数）会把工具 schema 变成黑盒（`op: string + params: object`），
  LLM 不知道每种 `op` 要传什么参数，调用准确率低
- Solon MCP 原生支持一个端点多工具（`MethodToolProvider` 自动注册整个 Bean 的所有
  `@ToolMapping` 方法），扩展操作 = 加一个方法，零工厂零注册表
- 强类型参数（`String table` / `List<String> columns` / `Map<String,Object> set`）
  编译期可校验，MCP schema 自动推导，LLM 调用准确率最高

**端点下 7 个工具**（详见 [database-tools.md](database-tools.md)）：
`ping` / `executeReadonlySql` / `selectRow` / `selectRows` / `countRows` / `distinctQuery` / `groupByQuery`
（纯查询，写类/DDL 已移除；Gaarason 数据源通道查询走 `config.gaarason` 包）

## 五、已知约束

- 端点路径固定在 `/mcp/*`、`/sse/*`（SolonServletFilter URL 模式）
- 端点 channel = STREAMABLE_STATELESS（集群友好，无 session）
- Solon 扫描禁用（`enableScanning(false)`），MCP 端点必须通过 Spring Bean 手动构建
- `McpAuthFilter` 的 `TokenVerifier`/`TokenExtractor` 是 Spring Bean，由
  `McpServerConfig` 构造器注入后手动 `new McpAuthFilter(...)` 传入 Solon FilterChain
- `MethodToolProvider` 自动注册整个 Bean 的所有 `@ToolMapping` 方法，无需逐个注册

## 六、客户端接入示例

MCP 客户端（Streamable Stateless）：

```
POST http://host:8080/mcp/database-tools
Content-Type: application/json
Accept: application/json, text/event-stream
Authorization: Bearer <token>

{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
```

工具调用（走 Gaarason 数据源通道，参数化查询）：

```
POST http://host:8080/mcp/database-tools
Content-Type: application/json
Accept: application/json, text/event-stream

{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"selectRow","arguments":{"sql":"SELECT * FROM users WHERE id = ?","args":[1]}}}
```

响应（Result 包装，详见 [unified-response-exception.md](unified-response-exception.md)）：

```json
{"success":true,"code":0,"data":{"id":1,"name":"...","...":"..."}}
```
