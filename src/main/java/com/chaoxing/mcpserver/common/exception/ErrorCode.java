package com.chaoxing.mcpserver.common.exception;

/**
 * 统一业务错误码。
 * <p>
 * 调用方（MCP 客户端 / 前端）只认 {@link #code}，不直接读 {@code SQLException} 的
 * SQLState / 厂商错误码。MCP 工具方法把 {@link DbException} 转为
 * {@code Result.fail(errorCode, safeMessage)} 返回。
 * </p>
 */
public enum ErrorCode {

    // ------------------------------------------------------------------
    // 数据库类（10xx）
    // ------------------------------------------------------------------

    /** 数据库连接失败（无法取得连接）。 */
    DB_CONNECTION_FAILED(1001, "database connection failed"),

    /** 连接池耗尽（等待可用连接超时）。 */
    DB_POOL_EXHAUSTED(1002, "connection pool exhausted"),

    /** 查询超时（单条语句执行超过 mcp.db.statement-timeout-seconds）。 */
    DB_TIMEOUT(1003, "database statement timeout"),

    /** 唯一键冲突（duplicate key / unique constraint violation）。 */
    DB_UNIQUE_KEY_CONFLICT(1004, "unique key conflict"),

    /** 死锁（deadlock / serialization failure）。 */
    DB_DEADLOCK(1005, "deadlock detected"),

    /** 外键约束冲突。 */
    DB_FOREIGN_KEY_VIOLATION(1006, "foreign key constraint violated"),

    /** 表不存在。 */
    DB_TABLE_NOT_FOUND(1007, "table not found"),

    /** 列不存在。 */
    DB_COLUMN_NOT_FOUND(1008, "column not found"),

    /** SQL 语法错误。 */
    DB_SYNTAX_ERROR(1009, "sql syntax error"),

    /** 数据库认证失败（账号/密码错误）。 */
    DB_AUTH_FAILED(1010, "database authentication failed"),

    /** 数据库（schema）不存在。 */
    DB_SCHEMA_NOT_FOUND(1011, "database schema not found"),

    /** 权限不足（数据库用户无操作权限）。 */
    DB_PERMISSION_DENIED(1012, "database permission denied"),

    /** 锁等待超时（lock wait timeout，介于超时与死锁之间）。 */
    DB_LOCK_WAIT_TIMEOUT(1013, "lock wait timeout"),

    /** 未知/兜底数据库错误。 */
    DB_UNKNOWN(1099, "database error (unknown)"),

    // ------------------------------------------------------------------
    // 工具层（20xx）
    // ------------------------------------------------------------------

    /** 只读 SQL 防护拦截（非 SELECT/WITH、多语句、含危险关键字或注释）。 */
    TOOL_UNSAFE_SQL(2001, "readonly sql guard rejected the statement"),

    /** 工具被配置禁用（mcp.db.readonly-enabled=false）。 */
    TOOL_DISABLED(2002, "db tool disabled by configuration"),

    /** 结果序列化失败。 */
    TOOL_SERIALIZE_FAILED(2003, "failed to serialize db result"),

    // ------------------------------------------------------------------
    // 鉴权（40xx）
    // ------------------------------------------------------------------

    /** 鉴权未通过（401 语义）。 */
    AUTH_UNAUTHORIZED(4001, "unauthorized"),

    /** 鉴权拒绝（403 语义）。 */
    AUTH_FORBIDDEN(4002, "forbidden");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
