# 统一响应包装 + 统一异常处理（common 包）

## 一、设计问题

数据库报错五花八门（主键冲突、连接超时、死锁），直接抛给 MCP 客户端会暴露
SQLState、SQL 文本、堆栈等敏感信息。同时 MCP 工具方法返回类型不统一（有的返回
JSON 字符串，有的返回 Map，有的直接抛异常），客户端难以一致解析。

**封装做法**：
1. **统一响应**：所有 MCP 工具方法返回 `Result<T>` 序列化的 JSON 字符串，
   客户端只认 `success` + `code` + `message` + `data` 四个字段
2. **统一异常**：底座把底层 `SQLException` 转换成统一业务异常 `DbException`
   （code + safeMessage + cause），业务层只 `catch (DbException e)`，不读 SQLState

## 二、包结构

```
com.chaoxing.mcpserver.common
├── result/
│   └── Result.java              泛型统一响应（success / code / message / data）
└── exception/
    ├── ErrorCode.java           统一错误码枚举（10xx 数据库 / 20xx 工具 / 40xx 鉴权）
    ├── DbException.java         统一业务异常（code + safeMessage + cause）
    └── DbExceptionMapper.java   SQLException → DbException 映射器（SQLState + 厂商 code 双维度）
```

## 三、Result 统一响应

### 结构

```java
public class Result<T> {
    private boolean success;   // 是否成功
    private int code;          // 错误码（0=成功，见 ErrorCode）
    private String message;    // 安全信息（已脱敏）
    private T data;           // 业务数据（null 时不输出）

    // 工厂方法
    static Result<T> ok();
    static <T> Result<T> ok(T data);
    static <T> Result<T> ok(T data, String message);
    static <T> Result<T> fail(int code, String message);
    static <T> Result<T> fail(int code, String message, T data);
    static <T> Result<T> fromDbException(DbException e);

    // 序列化辅助（null 字段不出现，MCP 工具方法返回 JSON 字符串）
    Map<String, Object> toMap();
}
```

### 响应示例

```jsonc
// 成功
{ "success": true, "code": 0, "data": { "rowCount": 1, "truncated": false, "data": [ ... ] } }
// 失败
{ "success": false, "code": 2001, "message": "readonly tool only allows SELECT or WITH (CTE) ..." }
```

### 约定

- `Result.ok(data)` / `Result.fail(code, message)` / `Result.fromDbException(e)`
- `toMap()` 序列化时 null 字段不出现（`data`/`message` 为空不输出）
- 业务方只认 `success` + `code` + `message` + `data`，不读底层 SQLState

## 四、DbException 统一业务异常

### 结构

```java
public class DbException extends RuntimeException {
    private final int code;            // 业务错误码（对应 ErrorCode.getCode()）
    private final String safeMessage;  // 对外安全信息（已脱敏，无 SQL 文本/堆栈）

    // 构造器
    public DbException(int code, String safeMessage, Throwable cause);
    public DbException(ErrorCode errorCode, Throwable cause);

    // 工厂
    public DbException withSafeMessage(String newSafeMessage);
}
```

### 约定

- `getSafeMessage()` 只带 `ErrorCode.getDefaultMessage()`，**不含 SQL 文本 / 堆栈 / SQLState**
- 原始 `SQLException` 作为 `cause`（仅日志检索用），MCP 响应里不暴露
- 业务层只 `catch (DbException e)`，读 `e.getCode()` + `e.getSafeMessage()`，不读 SQLState

## 五、ErrorCode 错误码枚举

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
| 2003 | TOOL_SERIALIZE_FAILED | 结果序列化失败 |
| 4001 | AUTH_UNAUTHORIZED | 鉴权 401 |
| 4002 | AUTH_FORBIDDEN | 鉴权 403 |

## 六、DbExceptionMapper 映射规则

- 先按 **SQLState 前缀**匹配（XOPEN 标准，厂商无关）；
- SQLState 为空/未知时按 **MySQL 厂商 errorCode** 匹配；
- 都匹配不到落 `DB_UNKNOWN`；
- **新增厂商支持**：在 `SQLSTATE_*_MAP` / `MYSQL_VENDOR_CODE_MAP` 加项即可，
  不用改 `DbQueryService` / 工具方法

### 映射示例

| SQLException 特征 | 映射 ErrorCode |
|---|---|
| SQLState `08001` | DB_CONNECTION_FAILED（1001） |
| SQLState `08006`/`08007` / 消息含 "Connection is not available" | DB_POOL_EXHAUSTED（1002） |
| SQLState `23505` / MySQL vendorCode 1062 | DB_UNIQUE_KEY_CONFLICT（1004） |
| SQLState `41001` / MySQL vendorCode 1213 | DB_DEADLOCK（1005） |
| SQLState `23000`/`23503` / MySQL vendorCode 1452 | DB_FOREIGN_KEY_VIOLATION（1006） |
| SQLState `42S02` / MySQL vendorCode 1146 | DB_TABLE_NOT_FOUND（1007） |
| SQLState `42000` / MySQL vendorCode 1064 | DB_SYNTAX_ERROR（1009） |

## 七、业务层用法

```java
try {
    var result = dbQueryService.executeReadonly(sql);   // 拿连接只经 ConnectionProvider
} catch (DbException e) {
    // 只读 e.getCode() + e.getSafeMessage()，不碰 SQLState
    return Result.fromDbException(e);
}
```

工具方法统一捕获 `DbException`，经 `Result.fromDbException(e)` 包装后序列化返回：

```java
@ToolMapping(description = "...")
public String someTool(@Param(...) String arg) {
    try {
        var result = dbQueryService.executeReadonly(arg);
        return writeJson(Result.ok(result));
    } catch (DbException e) {
        log.error("[db] failed: code={}, safeMessage={}", e.getCode(), e.getSafeMessage());
        return writeJson(Result.fromDbException(e));
    }
}
```

## 八、单元测试

`DbExceptionMapperTest`（11 项）覆盖：
- SQLState 前缀映射（08001 / 23505 / 41001 / 42S02 / 3D000 / 42000）
- SQLState 精确映射（08006 / 08007 / 23000 / 23503）
- MySQL 厂商 code 映射（1045 / 1042 / 1054 / 1062 / 1064 / 1146 / 1205 / 1213 / 1452）
- 兜底 DB_UNKNOWN
- 连接失败特殊映射（"Connection is not available" → 1002）
- safeMessage 脱敏验证（不含 SQL 文本 / SQLState）

`ResultTest`（5 项）覆盖 `ok` / `fail` / `fromDbException` / `toMap` null 字段省略。
