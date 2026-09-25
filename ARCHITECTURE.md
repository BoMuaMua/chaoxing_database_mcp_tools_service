# MCP 基座 — 架构与模块说明

## 一、整体架构（Spring Boot + Solon 双容器混跑）

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
MCP 端点（@McpServerEndpoint + @ToolMapping 方法）
```

## 二、模块划分

### 1. MCP 端点（`com.chaoxing.mcpserver.mcp`）

| 文件 | 职责 |
|---|---|
| `McpServerConfig.java` | Spring @Configuration；@PostConstruct 中 Solon.start() + app.router().filter(McpAuthFilter) + 手动构建端点 provider；@PreDestroy 停止 Solon；FilterRegistrationBean 注册 SolonServletFilter |
| `McpDatabaseToolsEndpoint.java` | MCP 端点（/mcp/database-tools），暴露数据库工具（@ToolMapping） |
| `McpDatabasePropertiesConfiguration.java` | 绑定 mcp.db.* 配置，提供兜底 ObjectMapper |

**工具（@ToolMapping 方法）**：

| 工具 | 说明 |
|---|---|
| `ping(marker)` | 占位健康检查，验证 MCP 链路贯通 |
| `executeReadonlySql(sql)` | 第一个真实数据库工具：安全只读 SQL 查询（SELECT/WITH），结果 JSON 化返回，超行数截断 |

### 2. Auth 鉴权（`com.chaoxing.mcpserver.auth`）

| 文件 | 职责 |
|---|---|
| `TokenVerifier.java` | 校验接口：verify(String token, Context ctx) |
| `TokenExtractor.java` | 提取接口：extract(Context ctx) |
| `TokenVerificationResult.java` | 结果值对象（401/403 语义 + claims 扩展点） |
| `filter/McpAuthFilter.java` | Solon Filter，拦截 /mcp/*、/sse/*（挂载层，稳定不动） |
| `impl/NoopTokenVerifier.java` | 空置默认实现（pass / deny / custom 三模式） |
| `impl/DefaultTokenExtractor.java` | Bearer 头 → X-Auth-Token 头 → ?token= 查询串 |
| `config/AuthConfiguration.java` | Bean 装配（TokenExtractor + TokenVerifier + AuthProperties） |
| `config/AuthProperties.java` | 绑定 mcp.auth.* 配置 |
| `config/AuthMode.java` | pass \| deny \| custom 枚举 |

### 3. 数据库工具执行层（`com.chaoxing.mcpserver.db`）

| 文件 | 职责 |
|---|---|
| `DbQueryService.java` | 只读 SQL 查询服务（注入 DataSource + DbProperties + ObjectMapper），结果结构化 Map |
| `DataSourceProvider.java` | DataSource 薄封装（便于将来按端点/工具切换数据源、只读从库路由） |
| `ReadonlySqlGuard.java` | 只读 SQL 安全防护（单语句、SELECT/WITH、禁危险关键字、禁注释） |
| `UnsafeSqlException.java` | 非安全 SQL 异常（工具方法捕获后转结构化错误） |
| `DbProperties.java` | 绑定 mcp.db.* 配置（readonly-enabled / max-rows / statement-timeout-seconds） |

## 三、配置契约

```yaml
# application.yml
mcp:
  auth:
    mode: pass                # pass（默认）| deny（灰度封禁）| custom（甲方逻辑落地后切这里）
    custom-verifier-bean: ""  # mode=custom 时，指定 TokenVerifier Bean 名
  db:
    readonly-enabled: true          # 只读查询工具总开关
    max-rows: 200                   # 单次查询最大返回行数（超出截断并标注 truncated=true）
    statement-timeout-seconds: 30   # 单条语句超时（秒，<=0 不限制）
```

运行时可用 JVM 参数覆盖：`-Dmcp.auth.mode=deny` / `-Dmcp.db.max-rows=5`。

## 四、已验证冒烟测试

| 测试 | 期望 | 实际 |
|---|---|---|
| pass 模式 `tools/list` | 200 + 工具列表 | ✅ |
| pass 模式 `tools/call ping` | 200 + pong | ✅ |
| deny 模式 `tools/list`（无 token） | 401 unauthorized JSON | ✅ |
| deny 模式 `GET /sse` | 401 | ✅ |
| `executeReadonlySql` `SELECT 1` | 进入 DB（占位 DataSource 连接超时则 db_error） | ✅ |
| `executeReadonlySql` `DROP TABLE` | unsafe_sql 拒绝 | ✅ |
| `executeReadonlySql` `UPDATE` | unsafe_sql 拒绝 | ✅ |
| `executeReadonlySql` 多语句 `SELECT 1; SELECT 2` | unsafe_sql: multiple statements | ✅ |
| `executeReadonlySql` CTE 内 DELETE | unsafe_sql: forbidden keyword | ✅ |
| `executeReadonlySql` 含注释 | unsafe_sql: comments not allowed | ✅ |

## 五、已知约束

- 端点路径固定在 `/mcp/*`、`/sse/*`（SolonServletFilter URL 模式）
- 端点 channel = STREAMABLE_STATELESS（集群友好，无 session）
- Solon 扫描禁用（`enableScanning(false)`），MCP 端点必须通过 Spring Bean 手动构建
- `McpAuthFilter` 的 `TokenVerifier`/`TokenExtractor` 是 Spring Bean，由 `McpServerConfig` 构造器注入后手动 `new` 传入 Solon FilterChain
- DataSource 当前为占位配置（`127.0.0.1:3306/mcp_tools`），甲方连接信息到位后替换
- 只读 SQL 工具是白名单（SELECT/WITH）+ 关键字黑名单防护；写类工具（INSERT/UPDATE/DDL）需另加确认/白名单机制，不在本端点默认放行
