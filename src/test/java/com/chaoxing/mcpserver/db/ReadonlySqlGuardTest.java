package com.chaoxing.mcpserver.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ReadonlySqlGuard} 纯单元测试（不依赖数据库连接，开发期即可验证）。
 */
class ReadonlySqlGuardTest {

    @Test
    void selectAllowed() {
        assertEquals("SELECT 1", ReadonlySqlGuard.validate("SELECT 1"));
    }

    @Test
    void withCteAllowed() {
        assertEquals("WITH t AS (SELECT 1) SELECT * FROM t",
                ReadonlySqlGuard.validate("WITH t AS (SELECT 1) SELECT * FROM t;"));
    }

    @Test
    void trailingSemiColonStripped() {
        assertEquals("SELECT 1", ReadonlySqlGuard.validate("SELECT 1 ;"));
    }

    @Test
    void dropRejected() {
        assertThrows(UnsafeSqlException.class, () -> ReadonlySqlGuard.validate("DROP TABLE users"));
    }

    @Test
    void updateRejected() {
        assertThrows(UnsafeSqlException.class, () -> ReadonlySqlGuard.validate("UPDATE users SET x=1"));
    }

    @Test
    void multiStatementRejected() {
        assertThrows(UnsafeSqlException.class, () -> ReadonlySqlGuard.validate("SELECT 1; SELECT 2"));
    }

    @Test
    void cteWithDeleteRejected() {
        assertThrows(UnsafeSqlException.class,
                () -> ReadonlySqlGuard.validate("WITH t AS (SELECT 1) DELETE FROM x"));
    }

    @Test
    void commentRejected() {
        assertThrows(UnsafeSqlException.class, () -> ReadonlySqlGuard.validate("SELECT 1 -- hi"));
        assertThrows(UnsafeSqlException.class, () -> ReadonlySqlGuard.validate("SELECT /* x */ 1"));
    }

    @Test
    void emptyRejected() {
        assertThrows(UnsafeSqlException.class, () -> ReadonlySqlGuard.validate("   "));
    }
}
