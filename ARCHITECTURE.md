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
  │
  ▼
业务层（DbQueryService 等）
  │
  ├─ ConnectionProvider（config 包，对外一个 getConnection() 门面）
  │     → GaarasonDataSource（@Primary，路由）→ HikariDataSource（Spring 自动配置）
  │
  └─ SQLException → DbExceptionMapper.map() → DbException（统一业务异常，code=10xx/20xx/40xx）
        业务层只 catch DbException，不读 SQLState
  │
  ▼
Result<T>（common 包，统一响应包装）→ 序列化为 JSON 返回 MCP 客户端
```

## 二、模块划分

### 1. MCP 端点（`com.chaoxing.mcpserver.mcp`）

| 文件 | 职责 |
|---|---|
| `McpServerConfig.java` | Spring @Configuration；@PostConstruct 中 Solon.start() + app.router().filter(McpAuthFilter) + 手动构建端点 provider；@PreDestroy 停止 Solon；FilterRegistrationBean 注册 SolonServletFilter |
| `McpDatabaseToolsEndpoint.java` | MCP 端点（/mcp/database-tools），暴露数据库工具（@ToolMapping）；工具方法捕获 DbException，用 Result 包装响应 |
| `McpDatabasePropertiesConfiguration.java` | 绑定 mcp.db.* 配置，提供兜底 ObjectMapper |

**工具（@ToolMapping 方法）**：

| 工具 | 说明 |
|---|---|
| `ping(marker)` | 占位健康检查，验证 MCP 链路贯通 |
| `executeReadonlySql(sql)` | 安全只读 SQL 查询（SELECT/WITH），结果经 Result 包装 JSON 化返回，超行数截断 |

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
| `DbQueryService.java` | 只读 SQL 查询服务（经 ConnectionProvider 取连接，SQLException 经 DbExceptionMapper 归一化为 DbException） |
| `DbProperties.java` | 绑定 mcp.db.* 配置（readonly-enabled / max-rows / statement-timeout-seconds） |
| `ReadonlySqlGuard.java` | 只读 SQL 安全防护（单语句、SELECT/WITH、禁危险关键字、禁注释） |
| `UnsafeSqlException.java` | 非安全 SQL 异常（工具层拦截，code=2001） |

### 4. 统一响应/异常（`com.chaoxing.mcpserver.common`）

| 文件 | 职责 |
|---|---|
| `result/Result.java` | 泛型统一响应（success / code / message / data），静态工厂 ok()/fail()/fromDbException()；toMap() 序列化 |
| `exception/ErrorCode.java` | 统一错误码枚举（1001 连接失败 / 1002 池耗尽 / 1003 超时 / 1004 唯一键 / 1005 死锁 / 2001 只读拦截 / 4001 鉴权 401 / 4002 鉴权 403 等） |
| `exception/DbException.java` | 统一业务异常（code + safeMessage + cause）；业务层只 catch 它，不读 SQLState |
| `exception/DbExceptionMapper.java` | SQLException → DbException 映射器（SQLState 前缀 + MySQL 厂商 code 双维度，可扩展） |

### 5. 连接池统一配置（`com.chaoxing.mcpserver.config.pool`）

| 文件 | 职责 |
|---|---|
| `PoolProperties.java` | 绑定 spring.datasource.hikari.*（池参数统一读取出口，业务代码不直接读） |
| `ConnectionPoolConfig.java` | 启动日志打印池配置摘要；不手动定义 @Primary DataSource（避免与 Gaarason 冲突） |
| `ConnectionProvider.java` | 业务方"获取连接"唯一门面（注入 GaarasonDataSource @Primary，对外一个 getConnection()；取连接失败统一抛 DbException） |

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

spring:
  datasource:
    url: jdbc:mysql://...           # 甲方连接信息到位后替换
    driver-class-name: com.mysql.cj.jdbc.Driver
    username: ...
    password: ...
    hikari:                         # 连接池参数（统一收敛，业务代码不直接读）
      pool-name: McpDatabasePool
      maximum-pool-size: 10
      minimum-idle: 1
      connection-timeout: 5000
      idle-timeout: 300000
      max-lifetime: 1800000
      connection-test-query: "SELECT 1"
      initialization-fail-timeout: -1   # 占位池不阻塞启动
```

运行时可用 JVM 参数覆盖：`-Dmcp.auth.mode=deny` / `-Dmcp.db.max-rows=5`。

## 四、统一异常契约

业务层只 `catch (DbException e)`，读 `e.getCode()` + `e.getSafeMessage()`，不读 SQLState / 厂商 code：

| code | ErrorCode | 场景 |
|---|---|---|
| 1001 | DB_CONNECTION_FAILED | 连接失败（08001 通信失败） |
| 1002 | DB_POOL_EXHAUSTED | 连接池耗尽（08006/08007 / "Connection is not available"） |
| 1003 | DB_TIMEOUT | 单条语句超时 |
| 1004 | DB_UNIQUE_KEY_CONFLICT | 唯一键冲突（23505 / MySQL 1062） |
| 1005 | DB_DEADLOCK | 死锁（41001 / MySQL 1213） |
| 1006 | DB_FOREIGN_KEY_VIOLATION | 外键约束冲突（23000/23503 / MySQL 1452） |
| 1007 | DB_TABLE_NOT_FOUND | 表不存在（42S02 / MySQL 1146） |
| 1008 | DB_COLUMN_NOT_FOUND | 列不存在（MySQL 1054） |
| 1009 | DB_SYNTAX_ERROR | SQL 语法错误（42000 / MySQL 1064） |
| 1010 | DB_AUTH_FAILED | 数据库认证失败（MySQL 1045） |
| 1011 | DB_SCHEMA_NOT_FOUND | 库不存在（3D000 / MySQL 1042/1044） |
| 1012 | DB_PERMISSION_DENIED | 权限不足（MySQL 1142） |
| 1013 | DB_LOCK_WAIT_TIMEOUT | 锁等待超时（MySQL 1205） |
| 1099 | DB_UNKNOWN | 兜底未知错误 |
| 2001 | TOOL_UNSAFE_SQL | 只读 SQL 防护拦截 |
| 2002 | TOOL_DISABLED | 工具被配置禁用 |
| 2003 | TOOL_SERIALIZE_FAILED | 结果序列化失败 |
| 4001 | AUTH_UNAUTHORIZED | 鉴权 401 |
| 4002 | AUTH_FORBIDDEN | 鉴权 403 |

**脱敏约定**：`DbException.safeMessage` 只带 `ErrorCode.getDefaultMessage()`，不含 SQL 文本 / 堆栈 / SQLState；原始 SQLException 作为 `cause`（日志检索用），MCP 响应里不暴露。

## 五、已验证冒烟测试

| 测试 | 期望 | 实际 |
|---|---|---|
| pass 模式 `tools/list` | 200 + 工具列表 | ✅ |
| pass 模式 `tools/call ping` | 200 + pong | ✅ |
| deny 模式 `tools/list`（无 token） | 401 unauthorized JSON | ✅ |
| deny 模式 `GET /sse` | 401 | ✅ |
| `executeReadonlySql` `SELECT 1` | Result 包装（占位 DataSource 连不上则 code=1001/1002） | ✅ |
| `executeReadonlySql` `DROP TABLE` | Result code=2001 拒绝 | ✅ |
| `executeReadonlySql` `UPDATE` | Result code=2001 拒绝 | ✅ |
| `executeReadonlySql` 多语句 `SELECT 1; SELECT 2` | Result code=2001 拒绝 | ✅ |
| `executeReadonlySql` CTE 内 DELETE | Result code=2001 拒绝 | ✅ |
| `executeReadonlySql` 含注释 | Result code=2001 拒绝 | ✅ |

## 六、已知约束

- 端点路径固定在 `/mcp/*`、`/sse/*`（SolonServletFilter URL 模式）
- 端点 channel = STREAMABLE_STATELESS（集群友好，无 session）
- Solon 扫描禁用（`enableScanning(false)`），MCP 端点必须通过 Spring Bean 手动构建
- `McpAuthFilter` 的 `TokenVerifier`/`TokenExtractor` 是 Spring Bean，由 `McpServerConfig` 构造器注入后手动 `new` 传入 Solon FilterChain
- 连接池：Spring Boot 自动配置 `HikariDataSource`（读 `spring.datasource.*`）；Gaarason starter 的 `GaarasonDataSource`（@Primary）包装它做路由；`ConnectionProvider` 注入 `GaarasonDataSource` 对外暴露 `getConnection()`
- DataSource 当前为占位配置（`127.0.0.1:3306/mcp_tools`），甲方连接信息到位后替换 `spring.datasource.*`
- 只读 SQL 工具是白名单（SELECT/WITH）+ 关键字黑名单防护；写类工具（INSERT/UPDATE/DDL）需另加确认/白名单机制，不在本端点默认放行
