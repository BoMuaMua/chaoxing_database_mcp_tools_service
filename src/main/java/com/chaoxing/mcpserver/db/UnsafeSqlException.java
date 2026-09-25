package com.chaoxing.mcpserver.db;

/**
 * 非安全只读 SQL 异常（被 {@link ReadonlySqlGuard} 抛出，工具方法捕获后返回结构化错误）。
 */
public class UnsafeSqlException extends RuntimeException {

    public UnsafeSqlException(String message) {
        super(message);
    }
}
