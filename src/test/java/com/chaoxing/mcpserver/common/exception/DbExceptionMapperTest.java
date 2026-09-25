package com.chaoxing.mcpserver.common.exception;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DbExceptionMapper} 纯单元测试（不依赖数据库，验证 SQLState / 厂商 code 映射）。
 */
class DbExceptionMapperTest {

    private final DbExceptionMapper mapper = new DbExceptionMapper();

    @Test
    void sqlstatePrefix080mapsToConnectionFailed() {
        SQLException ex = new SQLException("comm link fail", "08001", 0);
        assertEquals(ErrorCode.DB_CONNECTION_FAILED, mapper.resolve(ex));
    }

    @Test
    void sqlstate41001mapsToDeadlock() {
        SQLException ex = new SQLException("deadlock", "41001", 0);
        assertEquals(ErrorCode.DB_DEADLOCK, mapper.resolve(ex));
    }

    @Test
    void sqlstate23505mapsToUniqueKeyConflict() {
        SQLException ex = new SQLException("dup key", "23505", 0);
        assertEquals(ErrorCode.DB_UNIQUE_KEY_CONFLICT, mapper.resolve(ex));
    }

    @Test
    void mysqlVendorCode1213mapsToDeadlock() {
        // SQLState 为空，厂商 code=1213（ER_LOCK_DEADLOCK）
        SQLException ex = new SQLException("deadlock", null, 1213);
        assertEquals(ErrorCode.DB_DEADLOCK, mapper.resolve(ex));
    }

    @Test
    void mysqlVendorCode1062mapsToUniqueKeyConflict() {
        SQLException ex = new SQLException("dup entry", null, 1062);
        assertEquals(ErrorCode.DB_UNIQUE_KEY_CONFLICT, mapper.resolve(ex));
    }

    @Test
    void mysqlVendorCode1205mapsToLockWaitTimeout() {
        SQLException ex = new SQLException("lock wait", null, 1205);
        assertEquals(ErrorCode.DB_LOCK_WAIT_TIMEOUT, mapper.resolve(ex));
    }

    @Test
    void mysqlVendorCode1045mapsToAuthFailed() {
        SQLException ex = new SQLException("access denied", null, 1045);
        assertEquals(ErrorCode.DB_AUTH_FAILED, mapper.resolve(ex));
    }

    @Test
    void unknownMapsToDbUnknown() {
        SQLException ex = new SQLException("weird", "99999", 9999);
        assertEquals(ErrorCode.DB_UNKNOWN, mapper.resolve(ex));
    }

    @Test
    void mapReturnsDbExceptionWithSafeMessage() {
        SQLException ex = new SQLException("dup key on col secret", "23505", 1062);
        DbException mapped = mapper.map(ex);
        assertEquals(ErrorCode.DB_UNIQUE_KEY_CONFLICT.getCode(), mapped.getCode());
        // 安全信息不带 SQL 文本
        assertEquals(ErrorCode.DB_UNIQUE_KEY_CONFLICT.getDefaultMessage(), mapped.getSafeMessage());
        // 原始异常作为 cause（日志检索用）
        assertEquals(ex, mapped.getCause());
    }

    @Test
    void mapConnectionFailurePoolExhaustedByMessage() {
        SQLException ex = new SQLException("McpPlaceholderPool - Connection is not available", null, 0);
        DbException mapped = mapper.mapConnectionFailure(ex);
        assertEquals(ErrorCode.DB_POOL_EXHAUSTED.getCode(), mapped.getCode());
    }

    @Test
    void mapConnectionFailureSqlstate08007() {
        SQLException ex = new SQLException("pool limit", "08007", 0);
        DbException mapped = mapper.mapConnectionFailure(ex);
        assertEquals(ErrorCode.DB_POOL_EXHAUSTED.getCode(), mapped.getCode());
    }
}
