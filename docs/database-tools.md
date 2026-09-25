# 数据库工具 + Gaarason 查询封装（db + config.gaarason 包）

## 一、设计原则

**单一 MCP 端点 + 多个强类型查询工具方法**（纯查询，无写类/DDL）。端点仅一个
（`/mcp/database-tools`），端点下注册多个 `@ToolMapping` 工具，每个查询操作独立强类型
参数（MCP schema 友好，LLM 调用准确率高）；底层统一走 `GaarasonQueryTools`（强类型查询层）→
`GaarasonQueryService`（Gaarason 数据源通道）→ `DbExceptionMapper`（异常归一化）→
`Result`（响应包装）。

> **封装形式演进**：早期用自研 `JdbcExecutor`（JDBC 直查）封装 7 个常用操作（含写类/DDL）；
> 现按 Gaarason 方案改造——**废弃 JDBC 直查底座，改用 Gaarason 数据源通道执行查询**，
> 并**移除全部写类/DDL 工具**（只保留查询）。原因：
> 1. 查询连接统一走 Gaarason 路由数据源（`GaarasonDataSource`，`@Primary`），将来 Gaarason
>    切多数据源/读写分离时查询自动跟随路由，业务零改动；
> 2. Gaarason 7.0.15 没有"运行时按表名泛型解析 Model"的公开 API（Eloquent 对象查询强依赖
>    预编译 Model 类），故"对象操作"走 Gaarason 的**原生 SQL 执行通道**（参数化查询 +
>    Gaarason 数据源路由 + 方言/日志），不预编译实体类；
> 3. 写类/DDL 风险高，基座阶段默认不暴露（需另加确认/白名单/审计机制）。

## 二、MCP 端点工具清单（McpDatabaseToolsEndpoint）

`McpDatabaseToolsEndpoint`（`/mcp/database-tools`，STREAMABLE_STATELESS）暴露 7 个工具：

| 工具 | 说明 | 执行通道 | 安全开关 |
|---|---|---|---|
| `ping(marker)` | 占位健康检查，验证 MCP 链路贯通 | — | — |
| `executeReadonlySql(sql)` | 原始只读 SQL 查询（SELECT/WITH，单语句，超 max-rows 截断） | JDBC 直查（`DbQueryService`） | `mcp.db.readonly-enabled` |
| `selectRow(sql, args)` | SELECT 单行（多列 Map，无结果返回 null） | Gaarason 数据源 | `mcp.db.readonly-enabled` |
| `selectRows(sql, args)` | SELECT 多行（超 max-rows 截断并标注 truncated） | Gaarason 数据源 | `mcp.db.readonly-enabled` |
| `countRows(sql, args)` | SELECT COUNT(*) 计数 | Gaarason 数据源 | `mcp.db.readonly-enabled` |
| `distinctQuery(table, columns, filters, args)` | SELECT DISTINCT 去重查询（可带 WHERE 过滤） | Gaarason 数据源 | `mcp.db.readonly-enabled` |
| `groupByQuery(table, selectExpr, groupByExpr, filters, args)` | SELECT ... GROUP BY 分组聚合（可带 HAVING） | Gaarason 数据源 | `mcp.db.readonly-enabled` |

> 所有 Gaarason 通道查询：参数化 `?` 占位符（防 SQL 注入）；SELECT 类经 `ReadonlySqlGuard`
> 防护（单语句、只读、无危险关键字）；结果行数受 `mcp.db.max-rows` 截断。
> `executeReadonlySql` 是 JDBC 直查逃生舱（不经 Gaarason 路由），用于 Gaarason 数据源不可用时的诊断。

## 三、包结构

```
com.chaoxing.mcpserver.db            只读 SQL 查询执行层（JDBC 直查逃生舱）
├── DbQueryService.java             只读 SQL 查询服务（经 ConnectionProvider 取连接，
│                                    SQLException 经 DbExceptionMapper 归一化为 DbException）
├── DbProperties.java               绑定 mcp.db.*（readonly-enabled / max-rows /
│                                     statement-timeout-seconds）
├── ReadonlySqlGuard.java           只读 SQL 安全防护（单语句、SELECT/WITH、禁危险关键字、禁注释）
└── UnsafeSqlException.java         非安全 SQL 异常（工具层拦截，code=2001）

com.chaoxing.mcpserver.config.gaarason   Gaarason 查询封装（新包）
├── GaarasonQueryService.java       Gaarason 数据源通道查询执行层（参数化查询 +
│                                    结果集转 List<Map> + 异常归一化；仅查询，无写类）
└── GaarasonQueryTools.java         强类型查询工具层（selectRow / selectRows / count /
                                     distinct / groupBy，可扩展）
```

> 旧 `com.chaoxing.mcpserver.dbops` 包（`JdbcExecutor` + `DatabaseOpsService`，含写类/DDL）
> 已废弃删除，由 `config.gaarason` 包替代。

## 四、Gaarason 查询通道设计（config.gaarason）

**连接来源**：注入 Gaarason 的 `GaarasonDataSource`（`@Primary`，继承 `javax.sql.DataSource`，
内部包装 HikariCP 池做路由）。参数化 SQL 经 `Connection` + `PreparedStatement` 执行，
结果集逐行转 `Map<列名,值>`（保持列顺序，JSON 友好），异常经 `DbExceptionMapper` 归一化。

**已知坑（已处理）**：Gaarason 包装器取连接失败时抛的是
`gaarason.database.exception.SQLRuntimeException`（cause 里包着底层 `SQLException`），
不是 `SQLException`。`GaarasonQueryService.getGaarasonConnection()` 已处理：解 cause 链
还原 `SQLException` 再交给 `DbExceptionMapper`，业务层不会拿到 Gaarason 原始异常。

**结果集类型**：`List<Map<String, Object>>`（列名→值有序行列表），经 `Result.toMap()`
序列化为 JSON 返回 MCP 客户端（`data` 字段为行列表，`rowCount`/`truncated` 标注截断）。

## 五、GaarasonQueryTools 强类型查询方法清单

| 方法 | 操作 | 返回 | 安全护栏 |
|---|---|---|---|
| `selectRows(sql, args)` | SELECT 多行（截断标注） | `{rowCount,truncated,data}` | `ReadonlySqlGuard` + `max-rows` 截断 |
| `selectRow(sql, args)` | SELECT 单行 | 行 Map 或 null | `ReadonlySqlGuard` |
| `count(sql, args)` | SELECT COUNT(*) 计数 | 计数值 | `ReadonlySqlGuard` |
| `distinct(table, columns, filters, args)` | SELECT DISTINCT 去重（可带 WHERE） | `{rowCount,truncated,data}` | 表名/列名白名单校验（TODO）+ `ReadonlySqlGuard` |
| `groupBy(table, selectExpr, groupByExpr, filters, args)` | SELECT ... GROUP BY（可带 HAVING） | `{rowCount,truncated,data}` | 投影/分组表达式非空校验 + `ReadonlySqlGuard` |

**可扩展**：新增查询类型 = 在 `GaarasonQueryTools` 加一个强类型方法 + 端点加一个
`@ToolMapping`，底层统一走 `GaarasonQueryService`（Gaarason 数据源），零工厂零注册表。

## 六、只读 SQL 安全防护（ReadonlySqlGuard）

执行前经工具层防护，**不合法直接拒绝（code=2001），不进数据库**：

| 规则 | 示例 | 拒绝原因 |
|---|---|---|
| 非 SELECT/WITH 开头 | `DROP TABLE users` / `UPDATE ...` | 只读白名单 |
| 多语句 | `SELECT 1; SELECT 2` | 禁止拼接 |
| CTE 体内夹带写操作 | `WITH t AS (...) DELETE FROM x` | 危险关键字（INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE/CREATE/GRANT/REVOKE/RENAME/CALL/LOAD/REPLACE/MERGE） |
| 注释符 | `SELECT 1 -- hi` / `/* */` | 防 SQL 注入变形 |

> 写类工具（INSERT/UPDATE/DELETE/DDL）**已移除**，不在基座默认放行；需另加确认/白名单/审计机制后另行设计。

## 七、已验证冒烟测试（Gaarason 查询工具）

| 测试 | 期望 | 实际 |
|---|---|---|
| `tools/list` | 注册 7 个工具（无写类/DDL） | ✅ |
| `selectRows`（占位库连不上） | `Result{success:false,code:1001,message:"database connection failed"}` | ✅ |
| `selectRow`（占位库连不上） | `Result{success:false,code:1001}` | ✅ |
| `countRows`（占位库连不上） | `Result{success:false,code:1001}` | ✅ |
| `distinctQuery`（占位库连不上） | `Result{success:false,code:1001}` | ✅ |
| `groupByQuery`（占位库连不上） | `Result{success:false,code:1001}` | ✅ |
| `executeReadonlySql` `SELECT 1`（JDBC 直查，占位库连不上） | `Result{success:false,code:1001}` | ✅ |
| `executeReadonlySql` `DROP TABLE` | `Result{success:false,code:2001,message:"readonly tool only allows SELECT or WITH..."}` | ✅ |
| `executeReadonlySql` `UPDATE` | `Result{success:false,code:2001}` | ✅ |
| `executeReadonlySql` `SELECT 1; SELECT 2` | `Result{success:false,code:2001,message:"multiple statements..."}` | ✅ |
| `executeReadonlySql` `WITH ... DELETE` | `Result{success:false,code:2001,message:"forbidden keyword DELETE"}` | ✅ |
| `groupByQuery` 空 selectExpr | `Result{success:false,code:2001,message:"groupBy requires non-empty selectExpr and groupByExpr"}` | ✅ |
| `ping` | `pong: ok` | ✅ |

> 单元测试：`ReadonlySqlGuardTest`（9）+ `ResultTest`（5）+ `DbExceptionMapperTest`（11）
> + `McpserverApplicationTests`（1 Spring 容器集成）= 26 项全过（不依赖真实数据库，开发期即可跑）。
