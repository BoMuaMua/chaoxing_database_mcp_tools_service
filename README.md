# MCP Database Tools Server（MCP 基座）

MCP（Model Context Protocol）数据库工具基座服务：MCP 客户端（AI 智能体 / LLM 应用）通过 MCP 端点调用本服务，服务再查询数据库。

- 技术栈：Java 17 · Spring Boot 3.3.13 · solon-ai-mcp 3.9.5（MCP 端点由 Solon 容器管理，Spring Boot 提供 Web 容器）
- 数据库：`database-spring-boot-starter`（Gaarason）+ HikariCP 连接池
- 构建：Maven

```bash
# 编译
mvn -q clean compile
# 启动（默认 pass 模式）
mvn spring-boot:run
# 启动（deny 模式，联调灰度封禁）
mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Dmcp.auth.mode=deny"
```

---

# Auth 鉴权框架（调用方鉴权）

## 一、鉴权保护的是什么

本框架做 **调用方鉴权（caller-side auth）**：校验"谁有权调用这个 MCP 服务"，
保护 MCP 端点不被任意第三方直接访问。

**职责边界**：MCP 协议本身无法识别终端用户是谁（用户身份只能由智能体通过
工具参数 / 请求头透传，如 `@Header("user")` 或工具参数里的 `userId`）。
用户级的授权与审计属于**工具层职责**，不在 Auth 包范围内。

## 二、请求链（已生效）

```
HTTP 请求
  │
  ▼
Spring Tomcat（8080）
  │
  ├─ SolonServletFilter（FilterRegistrationBean 桥接 /mcp/*、/sse/* 到 Solon 容器）
  │
  ▼
Solon 容器 FilterChain
  │
  ├─ McpAuthFilter（Solon Filter，token 校验，先于 MCP 协议帧解析）
  │     不通过 → ctx.status(401/403) + output + setHandled(true) → 链路终止，
  │             MCP 协议层不可见未授权请求
  │
  ▼
MCP 端点（@McpServerEndpoint + @ToolMapping 工具方法）
  │
  ▼
工具执行层
  ├─ ReadonlySqlGuard（只读 SQL 防护，不合法 → code=2001）
  ├─ ConnectionProvider.getConnection()（唯一取连接门面，失败 → code=1001/1002）
  └─ SQLException → DbExceptionMapper.map() → DbException（统一业务异常，code=10xx）
  │
  ▼
Result<T>（统一响应包装）→ 序列化为 JSON 返回 MCP 客户端
```

MCP 客户端调用示例（Streamable Stateless）：

```
POST http://host:8080/mcp/database-tools
Content-Type: application/json
Accept: application/json, text/event-stream
Authorization: Bearer <token>

{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
```

## 三、包结构与职责

```
com.chaoxing.mcpserver.auth
├── TokenVerifier.java            校验核心接口：verify(String token, Context ctx)
├── TokenExtractor.java           提取接口：extract(Context ctx)
├── TokenVerificationResult.java  结果值对象（401/403 语义 + claims 扩展点）
├── filter/
│   └── McpAuthFilter.java        Solon Filter，拦截 /mcp/*、/sse/*（挂载层，稳定不动）
├── impl/
│   ├── NoopTokenVerifier.java    空置默认实现（pass / deny / custom 三模式）
│   └── DefaultTokenExtractor.java  Bearer 头 → X-Auth-Token 头 → ?token= 查询串
└── config/
    ├── AuthConfiguration.java    Bean 装配（TokenExtractor + TokenVerifier + AuthProperties）
    ├── AuthProperties.java       绑定 mcp.auth.* 配置（@EnableConfigurationProperties）
    └── AuthMode.java            pass | deny | custom 枚举

com.chaoxing.mcpserver.mcp
├── McpServerConfig.java          Solon.start() + app.router().filter(McpAuthFilter)
│                                 + 手动构建端点 provider + 注册 SolonServletFilter
├── McpDatabaseToolsEndpoint.java MCP 端点（/mcp/database-tools），暴露数据库工具（@ToolMapping）
└── McpDatabasePropertiesConfiguration.java  绑定 mcp.db.* + 兜底 ObjectMapper

com.chaoxing.mcpserver.db        数据库工具执行层
├── DbQueryService.java          只读 SQL 查询服务（经 ConnectionProvider 取连接，
│                                 SQLException 经 DbExceptionMapper 归一化为 DbException）
├── DbProperties.java            绑定 mcp.db.*（readonly-enabled / max-rows / statement-timeout-seconds）
├── ReadonlySqlGuard.java        只读 SQL 安全防护（单语句、SELECT/WITH、禁危险关键字、禁注释）
└── UnsafeSqlException.java      非安全 SQL 异常（工具层拦截，code=2001）

com.chaoxing.mcpserver.common    统一响应 + 统一异常
├── result/
│   └── Result.java              泛型统一响应（success / code / message / data）
└── exception/
    ├── ErrorCode.java           统一错误码枚举（10xx 数据库 / 20xx 工具 / 40xx 鉴权）
    ├── DbException.java         统一业务异常（code + safeMessage + cause）
    └── DbExceptionMapper.java   SQLException → DbException 映射器（SQLState + 厂商 code 双维度）

com.chaoxing.mcpserver.config    连接池统一配置
└── pool/
    ├── PoolProperties.java      绑定 spring.datasource.hikari.*（池参数统一出口）
    ├── ConnectionPoolConfig.java  池配置装配（不手动定义 @Primary DataSource）
    └── ConnectionProvider.java  业务方"获取连接"唯一门面（对外一个 getConnection()）
```

## 四、配置契约

```yaml
# application.yml
mcp:
  auth:
    mode: pass                # pass（默认，空置放行）| deny（空置拒绝）| custom（委托自定义实现）
    custom-verifier-bean: ""  # mode=custom 时，指定目标 TokenVerifier Bean 名

  db:
    readonly-enabled: true            # 只读查询工具总开关
    max-rows: 200                     # 单次查询最大返回行数（超出截断并标注 truncated=true）
    statement-timeout-seconds: 30     # 单条语句超时（秒，<=0 不限制）

spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/mcp_tools?...   # 甲方连接信息到位后替换
    driver-class-name: com.mysql.cj.jdbc.Driver
    username: root
    password: root
    hikari:                               # 连接池参数统一收敛（业务代码不直接读）
      pool-name: McpDatabasePool
      maximum-pool-size: 10
      minimum-idle: 0
      connection-timeout: 5000
      idle-timeout: 300000
      max-lifetime: 1800000
      connection-test-query: "SELECT 1"
      initialization-fail-timeout: -1     # 占位池不阻塞启动
```

运行时可用 JVM 参数覆盖：`-Dmcp.auth.mode=deny` / `-Dmcp.db.max-rows=5`。

### 行为矩阵（Auth）

| mode | token 携带 | 行为 | 日志 |
|---|---|---|---|
| pass | 有 | 放行 | DEBUG |
| pass | 无 | 放行 | WARN（提醒非安全模式） |
| deny | 任意 | 拒绝 401 | WARN |
| custom | 任意 | 委托 custom-verifier-bean 实现 | 取决于实现 |
| 任意 | 校验器抛异常 | 500（不当鉴权失败处理） | ERROR |

### 日志约定（Auth）

- 校验失败 → WARN，**绝不记录 token 明文**
- 校验通过 → DEBUG
- `/mcp/**/message`（Streamable 回发端点）不重复鉴权（SSE 连接建立时已验证）

---

# 数据库工具 + 统一响应 + 连接池 + 统一异常

## 五、数据库工具（首个端点）

`McpDatabaseToolsEndpoint`（`/mcp/database-tools`，STREAMABLE_STATELESS）暴露：

| 工具 | 说明 |
|---|---|
| `ping(marker)` | 占位健康检查，验证 MCP 链路贯通 |
| `executeReadonlySql(sql)` | 安全只读 SQL 查询（仅限 `SELECT`/`WITH`，单语句，结果 JSON 化；超 `max-rows` 截断） |

### 只读 SQL 安全防护（ReadonlySqlGuard）

执行前经工具层防护，**不合法直接拒绝（code=2001），不进数据库**：

| 规则 | 示例 | 拒绝原因 |
|---|---|---|
| 非 SELECT/WITH 开头 | `DROP TABLE users` / `UPDATE ...` | 只读白名单 |
| 多语句 | `SELECT 1; SELECT 2` | 禁止拼接 |
| CTE 体内夹带写操作 | `WITH t AS (...) DELETE FROM x` | 危险关键字（INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE/CREATE/GRANT/REVOKE/RENAME/CALL/LOAD/REPLACE/MERGE） |
| 注释符 | `SELECT 1 -- hi` / `/* */` | 防 SQL 注入变形 |

> 写类工具（INSERT/UPDATE/DDL）需另加确认/白名单/审计机制，**不在本端点默认放行**。

## 六、统一响应包装（common.result.Result）

所有 MCP 工具方法统一返回 `Result` 包装（序列化为 JSON 字符串给 MCP 客户端）：

```jsonc
// 成功
{ "success": true, "code": 0, "data": { "rowCount": 1, "truncated": false, "data": [ ... ] } }
// 失败
{ "success": false, "code": 2001, "message": "readonly tool only allows SELECT or WITH (CTE) ..." }
```

- `Result.ok(data)` / `Result.fail(code, message)` / `Result.fromDbException(e)`
- `toMap()` 序列化时 null 字段不出现（`data`/`message` 为空不输出）
- 业务方只认 `success` + `code` + `message` + `data`，不读底层 SQLState

## 七、统一异常处理（common.exception）

**问题**：数据库报错五花八门（主键冲突、连接超时、死锁），直接抛给前端会暴露敏感信息。
**封装做法**：底座把底层 `SQLException` 转换成统一业务异常 `DbException`，并定义好错误码。

```java
public class DbException extends RuntimeException {
    private int code;   // 比如：1001 连接失败，1002 池耗尽，1003 超时，1004 唯一键，1005 死锁
    // 业务层只 catch DbException，不用去认 SQLException 的 SQLState
}
```

### 错误码速查（ErrorCode）

| code | ErrorCode | 场景 |
|---|---|---|
| 1001 | DB_CONNECTION_FAILED | 连接失败（SQLState 08001 通信失败） |
| 1002 | DB_POOL_EXHAUSTED | 连接池耗尽（08006/08007 / HikariCP "Connection is not available"） |
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
| 4001 | AUTH_UNAUTHORIZED | 鉴权 401 |
| 4002 | AUTH_FORBIDDEN | 鉴权 403 |

### 映射规则（DbExceptionMapper）

- 先按 **SQLState 前缀**匹配（XOPEN 标准，厂商无关）；
- SQLState 为空/未知时按 **MySQL 厂商 errorCode** 匹配；
- 都匹配不到落 `DB_UNKNOWN`；
- **新增厂商支持**：在 `SQLSTATE_*_MAP` / `MYSQL_VENDOR_CODE_MAP` 加项即可，不用改 `DbQueryService` / 工具方法。

### 脱敏约定

- `DbException.getSafeMessage()` 只带 `ErrorCode.getDefaultMessage()`，**不含 SQL 文本 / 堆栈 / SQLState**；
- 原始 `SQLException` 作为 `cause`（仅日志检索用），MCP 响应里不暴露。

### 业务层用法

```java
try {
    var result = dbQueryService.executeReadonly(sql);   // 拿连接只经 ConnectionProvider
} catch (DbException e) {
    // 只读 e.getCode() + e.getSafeMessage()，不碰 SQLState
    return Result.fromDbException(e);
}
```

## 八、连接池统一配置（config.pool）

**问题**：业务方不该关心连接池参数（最大连接数、超时时间等）。
**封装做法**：统一配一个连接池（HikariCP），对外只暴露"获取连接"的方法。

```
业务代码
  │  只注入 ConnectionProvider，调 getConnection()
  ▼
ConnectionProvider（config.pool，唯一门面）
  │  取连接失败 → DbException（1001/1002）
  ▼
GaarasonDataSource（@Primary，路由）→ HikariDataSource（Spring Boot 自动配置，读 spring.datasource.*）
```

| 类 | 职责 |
|---|---|
| `ConnectionProvider` | 业务方"获取连接"**唯一入口**（对外一个 `getConnection()`）；不读池参数，不碰池实现；取连接失败统一转 `DbException` |
| `PoolProperties` | 绑定 `spring.datasource.hikari.*`（池参数统一读取出口；将来加连接池监控/动态调参从这里走） |
| `ConnectionPoolConfig` | 池配置装配（**不手动定义 `@Primary DataSource`**，避免与 Gaarason 的 `gaarasonDataSource` 撞车；Gaarason 通过 `ObjectProvider<DataSource>` 包装 Spring 自动配置的池做路由） |

> **已知坑**：Gaarason 数据源包装器取连接失败时抛的是 `gaarason.database.exception.SQLRuntimeException`（cause 里包着底层 `SQLException`），不是 `SQLException`。`ConnectionProvider` 已处理：解 cause 链还原 `SQLException` 再交给 `DbExceptionMapper`，业务层不会拿到 Gaarason 原始异常。

## 九、未来完善：写入真正的鉴权逻辑（甲方逻辑落地步骤）

> 原则：挂载层（`McpAuthFilter` / `McpServerConfig`）稳定不动，
> 只需新增实现类 + 切配置，零侵入。

**Step 1 — 新增 TokenVerifier 实现类**

放在 `com.chaoxing.mcpserver` 包下（Spring 组件扫描范围），加 `@Component` 注册为 Bean：

```java
package com.chaoxing.mcpserver.auth.impl;

import com.chaoxing.mcpserver.auth.TokenVerificationResult;
import com.chaoxing.mcpserver.auth.TokenVerifier;
import org.noear.solon.core.handle.Context;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 甲方提供的加密/签名校验逻辑（示例骨架）。 */
@Component
public class CryptoTokenVerifier implements TokenVerifier {

    @Override
    public TokenVerificationResult verify(String token, Context ctx) {
        if (token == null || token.isBlank()) {
            return TokenVerificationResult.unauthorized("token missing");
        }
        // TODO: 调用甲方加密/签名校验（如验签、查调用方白名单等）
        boolean ok = cryptoCheck(token);
        if (!ok) {
            return TokenVerificationResult.unauthorized("token signature invalid");
        }
        // 可选：携带调用方声明（claims），由过滤器写入 ctx.attrSet("mcp.auth.claims", ...)
        return TokenVerificationResult.pass(Map.of("clientName", "some-caller"));
    }

    private boolean cryptoCheck(String token) {
        return true; // 甲方逻辑
    }
}
```

要点：
- `TokenVerificationResult` 工厂方法：`pass()` / `pass(claims)` / `unauthorized(reason)` / `forbidden(reason)`
- `Context` 是扩展点：实现方可读取 `ctx.header(...)` / `ctx.param(...)` / `ctx.pathNew()` 等元数据
- reason 会出现在 WARN 日志与 401/403 响应体中，**禁止包含 token 明文**

**Step 2 — 切换配置**

```yaml
mcp:
  auth:
    mode: custom
    custom-verifier-bean: cryptoTokenVerifier   # 新实现类的 Bean 名（默认取类名首字母小写）
```

**Step 3 — 验证**

启动后：不带 token → 401；带合法 token → 200 tools 列表；带非法 token → 401/403。

`NoopTokenVerifier` 的 CUSTOM 分支通过 `beanFactory.isTypeMatch(beanName, TokenVerifier.class)`
精确解析目标 Bean 并委托校验；Bean 不存在或类型不符时拒绝请求并打 WARN/ERROR。

## 十、已验证冒烟测试

| 测试 | 期望 | 实际 |
|---|---|---|
| pass 模式 `tools/list` | 200 + tools 列表 | ✅ |
| pass 模式 `tools/call ping` | 200 + pong | ✅ |
| deny 模式 `tools/list`（无 token） | 401 unauthorized JSON | ✅ |
| deny 模式 `GET /sse` | 401 | ✅ |
| `executeReadonlySql` `SELECT 1`（占位库连不上） | `Result{success:false,code:1001,message:"database connection failed"}` | ✅ |
| `executeReadonlySql` `DROP TABLE` | `Result{success:false,code:2001,message:"readonly tool only allows SELECT or WITH..."}` | ✅ |
| `executeReadonlySql` `UPDATE` | `Result{success:false,code:2001}` | ✅ |
| `executeReadonlySql` `SELECT 1; SELECT 2` | `Result{success:false,code:2001,message:"multiple statements..."}` | ✅ |
| `executeReadonlySql` `WITH ... DELETE` | `Result{success:false,code:2001,message:"forbidden keyword DELETE"}` | ✅ |

> 单元测试：`ReadonlySqlGuardTest`（9）+ `ResultTest`（5）+ `DbExceptionMapperTest`（11）= 25 项全过（不依赖真实数据库，开发期即可跑）。

## 十一、已知约束

- 端点路径固定在 `/mcp/*`、`/sse/*`（SolonServletFilter URL 模式）
- 端点 channel = STREAMABLE_STATELESS（集群友好，无 session）
- Solon 扫描禁用（`enableScanning(false)`），MCP 端点必须通过 Spring Bean 手动构建
- `McpAuthFilter` 的 `TokenVerifier`/`TokenExtractor` 是 Spring Bean，由 `McpServerConfig`
  构造器注入后手动 `new McpAuthFilter(...)` 传入 Solon FilterChain
- **连接池**：Spring Boot 自动配置 `HikariDataSource`（读 `spring.datasource.hikari.*`）；
  Gaarason 的 `GaarasonDataSource`（`@Primary`）包装它做路由；`ConnectionProvider` 注入
  Gaarason 路由数据源，对外暴露唯一 `getConnection()`
- DataSource 当前为占位配置（`127.0.0.1:3306/mcp_tools`），甲方连接信息到位后替换
  `spring.datasource.*`（url/username/password），**池参数无需改动**（已收敛到 `spring.datasource.hikari.*`）
- 只读 SQL 工具是白名单（SELECT/WITH）+ 关键字黑名单防护；写类工具需另加确认/白名单机制，不在本端点默认放行
