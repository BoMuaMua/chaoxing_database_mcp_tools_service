package com.chaoxing.mcpserver.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.SQLException;

/**
 * {@link SQLException} → {@link DbException} 的统一映射器。
 * <p>
 * 设计原则：
 * <ol>
 *   <li>先按 <b>SQLState</b> 前缀匹配（厂商无关，XOPEN 标准）；</li>
 *   <li>SQLState 为空或未知时，按 <b>厂商 errorCode</b> 匹配（MySQL 为例，见
 *       {@link #VENDOR_STATE_MAP} / {@link #VENDOR_CODE_MAP}）；</li>
 *   <li>都匹配不到时落到 {@link ErrorCode#DB_UNKNOWN}，并把 SQLState/errorCode 写进日志。</li>
 * </ol>
 * 新增厂商支持：在两个 Map 里加对应项即可，不用改 {@link DbQueryService} / 工具方法。
 * </p>
 * <p>
 * <b>敏感信息脱敏</b>：{@link DbException#getSafeMessage()} 只带 {@link ErrorCode#getDefaultMessage()}；
 * 原始 SQL / SQLState / 厂商 code / 堆栈 只写进 {@link DbException#getCause()}，供日志检索，
 * 不出现在 MCP 响应里。
 * </p>
 */
@Slf4j
@Component
public class DbExceptionMapper {

    /**
     * 把 {@link SQLException} 映射成 {@link DbException}（带 code + 安全信息 + 原始 cause）。
     */
    public DbException map(SQLException ex) {
        if (ex == null) {
            return new DbException(ErrorCode.DB_UNKNOWN, null);
        }
        ErrorCode code = resolve(ex);
        log.warn("[db] SQLException mapped: sqlstate={}, vendorCode={}, errorText={}",
                ex.getSQLState(), ex.getErrorCode(), ex.getMessage());
        return new DbException(code, ex);
    }

    /**
     * 解析错误码（SQLState 优先，厂商 code 兜底，未知落 {@link ErrorCode#DB_UNKNOWN}）。
     */
    public ErrorCode resolve(SQLException ex) {
        String state = ex.getSQLState();
        int vendorCode = ex.getErrorCode();

        // 1) SQLState 前缀匹配（XOPEN 标准类）
        if (state != null && !state.isBlank()) {
            String upper = state.toUpperCase(java.util.Locale.ROOT);
            for (java.util.Map.Entry<String, ErrorCode> e : SQLSTATE_PREFIX_MAP.entrySet()) {
                if (upper.startsWith(e.getKey())) {
                    return e.getValue();
                }
            }
            // 精确匹配（覆盖前缀未覆盖的厂商扩展）
            for (java.util.Map.Entry<String, ErrorCode> e : SQLSTATE_EXACT_MAP.entrySet()) {
                if (upper.equals(e.getKey())) {
                    return e.getValue();
                }
            }
        }

        // 2) 厂商 errorCode 匹配（MySQL）
        for (java.util.Map.Entry<Integer, ErrorCode> e : MYSQL_VENDOR_CODE_MAP.entrySet()) {
            if (e.getKey() == vendorCode) {
                return e.getValue();
            }
        }

        // 3) 兜底
        return ErrorCode.DB_UNKNOWN;
    }

    // ------------------------------------------------------------------
    // SQLState 映射（XOPEN 标准 + 厂商扩展）
    // 参考：https://dev.mysql.com/doc/refman/8.0/en/err-communication.html
    //       https://dev.mysql.com/doc/refman/8.0/en/error.html
    // ------------------------------------------------------------------

    /** SQLState 前缀 → 错误码（XOPEN 标准类）。 */
    private static final java.util.Map<String, ErrorCode> SQLSTATE_PREFIX_MAP = buildSqlstatePrefixMap();

    /** SQLState 精确匹配（覆盖前缀匹配未覆盖的厂商扩展）。 */
    private static final java.util.Map<String, ErrorCode> SQLSTATE_EXACT_MAP = buildSqlstateExactMap();

    /** MySQL 厂商 errorCode → 错误码。 */
    private static final java.util.Map<Integer, ErrorCode> MYSQL_VENDOR_CODE_MAP = buildMysqlVendorCodeMap();

    private static java.util.Map<String, ErrorCode> buildSqlstatePrefixMap() {
        java.util.Map<String, ErrorCode> m = new java.util.HashMap<>();
        // 08 = 连接异常
        m.put("080", ErrorCode.DB_CONNECTION_FAILED);   // 08001 通信失败, 08006 连接断开, 08007 连接池空
        // 41 = 事务异常（含死锁/序列化失败）
        m.put("410", ErrorCode.DB_DEADLOCK);            // 41001 死锁/序列化失败
        // 23 = 完整性约束异常
        m.put("235", ErrorCode.DB_UNIQUE_KEY_CONFLICT); // 23505 唯一键冲突
        m.put("230", ErrorCode.DB_FOREIGN_KEY_VIOLATION); // 23000/23503 外键
        // 42 = 语法/语义异常
        m.put("420", ErrorCode.DB_SYNTAX_ERROR);        // 42000 语法错
        m.put("42S", ErrorCode.DB_TABLE_NOT_FOUND);      // 42S02 表/列不存在
        // 0A = 驱动异常（无对应 XOPEN 码，落到未知）
        m.put("0A0", ErrorCode.DB_UNKNOWN);
        // 3D = schema 不存在
        m.put("3D0", ErrorCode.DB_SCHEMA_NOT_FOUND);     // 3D000
        // 3F = 无法开始事务
        m.put("3F0", ErrorCode.DB_UNKNOWN);
        return m;
    }

    private static java.util.Map<String, ErrorCode> buildSqlstateExactMap() {
        java.util.Map<String, ErrorCode> m = new java.util.HashMap<>();
        m.put("08S01", ErrorCode.DB_TIMEOUT);            // 通信链路超时
        m.put("HY000", ErrorCode.DB_UNKNOWN);            // 通用
        return m;
    }

    private static java.util.Map<Integer, ErrorCode> buildMysqlVendorCodeMap() {
        java.util.Map<Integer, ErrorCode> m = new java.util.HashMap<>();
        m.put(1045, ErrorCode.DB_AUTH_FAILED);           // ER_ACCESS_DENIED_ERROR
        m.put(1042, ErrorCode.DB_SCHEMA_NOT_FOUND);      // ER_BAD_DB_ERROR
        m.put(1044, ErrorCode.DB_SCHEMA_NOT_FOUND);      // ER_BAD_DB_ERROR(alt)
        m.put(1054, ErrorCode.DB_COLUMN_NOT_FOUND);      // ER_BAD_FIELD_ERROR
        m.put(1062, ErrorCode.DB_UNIQUE_KEY_CONFLICT);   // ER_DUP_ENTRY
        m.put(1064, ErrorCode.DB_SYNTAX_ERROR);          // ER_PARSE_ERROR
        m.put(1146, ErrorCode.DB_TABLE_NOT_FOUND);       // ER_NO_SUCH_TABLE
        m.put(1142, ErrorCode.DB_PERMISSION_DENIED);     // ER_COMMAND_DENIED_ERROR
        m.put(1205, ErrorCode.DB_LOCK_WAIT_TIMEOUT);     // ER_LOCK_WAIT_TIMEOUT
        m.put(1213, ErrorCode.DB_DEADLOCK);              // ER_LOCK_DEADLOCK
        m.put(1265, ErrorCode.DB_SYNTAX_ERROR);          // data too long
        m.put(1452, ErrorCode.DB_FOREIGN_KEY_VIOLATION); // ER_NO_REFERENCED_ROW_2
        return m;
    }

    /**
     * 把"获取连接失败"的 SQLException 归一化为 DbException（code=1002 DB_POOL_EXHAUSTED 或
     * 1001 DB_CONNECTION_FAILED）。业务代码通过 {@code ConnectionProvider.getConnection()}
     * 取连接，不需要自己 catch SQLException。
     */
    public DbException mapConnectionFailure(SQLException ex) {
        // 08xxx = 连接异常（XOPEN 标准类）
        String state = ex.getSQLState();
        int vendorCode = ex.getErrorCode();
        if (state != null && state.startsWith("08")) {
            // 08006 连接被断 / 08007 池空（connection limit exceeded）
            ErrorCode code = "08007".equals(state) || "08006".equals(state)
                    ? ErrorCode.DB_POOL_EXHAUSTED
                    : ErrorCode.DB_CONNECTION_FAILED;
            log.warn("[pool] getConnection failed: sqlstate={}, vendorCode={}, msg={}",
                    state, vendorCode, ex.getMessage());
            return new DbException(code, ex);
        }
        // HikariCP 池耗尽（SQLState=null，vendorCode=0，message 含 "Connection is not available"）
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        if (msg.contains("Connection is not available") || msg.contains("pool exhausted")) {
            log.warn("[pool] getConnection pool-exhausted: msg={}", msg);
            return new DbException(ErrorCode.DB_POOL_EXHAUSTED, ex);
        }
        // 兜底：连接类失败
        log.warn("[pool] getConnection failed (unknown): msg={}", msg);
        return new DbException(ErrorCode.DB_CONNECTION_FAILED, ex);
    }

    /**
     * 兼容旧调用（非 SQL 异常直接转 DbException）。
     */
    public DbException wrap(Throwable cause, ErrorCode code) {
        return new DbException(code, cause);
    }
}
