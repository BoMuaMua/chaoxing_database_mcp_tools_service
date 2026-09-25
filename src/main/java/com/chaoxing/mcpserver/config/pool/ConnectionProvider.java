package com.chaoxing.mcpserver.config.pool;

import com.chaoxing.mcpserver.common.exception.DbException;
import com.chaoxing.mcpserver.common.exception.DbExceptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 连接池对外门面（业务方唯一入口）。
 * <p>
 * 业务代码<b>只</b>注入本类，调用 {@link #getConnection()} 拿连接：
 * <pre>
 * &#8226; 不注入 HikariDataSource / DataSource；
 * &#8226; 不读池参数（最大连接数、超时等）；
 * &#8226; 不关心取连接的异常细节（统一归一化为 DbException）。
 * </pre>
 * 注入的是 <b>路由数据源</b>（{@code GaarasonDataSource}，{@code @Primary}）。
 * 它内部包装 Spring Boot 自动配置的 {@code HikariDataSource}；
 * Gaarason 包装器取连接失败时抛的是 <b>{@code gaarason.database.exception.SQLRuntimeException}</b>
 * （cause = 底层 {@code SQLException}），不是 {@code SQLException}，
 * 本类统一 catch 后交给 {@link DbExceptionMapper} 归一化为 {@link DbException}。
 * </p>
 * <p>
 * 将来要支持多数据源 / 读写分离：改本类内部路由，业务代码零改动。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectionProvider {

    /** Gaarason 路由数据源（{@code @Primary}，包装 HikariCP 池）。 */
    private final gaarason.database.contract.connection.GaarasonDataSource gaarasonDataSource;
    private final DbExceptionMapper exceptionMapper;

    /**
     * 获取数据库连接（业务方唯一对外方法）。
     * <p>取不到连接时抛 {@link DbException}，不暴露 {@link SQLException}。</p>
     */
    public Connection getConnection() {
        try {
            Connection conn = gaarasonDataSource.getConnection();
            if (log.isDebugEnabled()) {
                log.debug("[pool] connection acquired: {}", conn);
            }
            return conn;
        } catch (SQLException e) {
            // 直接来自 HikariCP 的 SQLException（Gaarason 透传场景）
            throw exceptionMapper.mapConnectionFailure(e);
        } catch (RuntimeException e) {
            // Gaarason 包装器取连接失败抛 SQLRuntimeException（cause = SQLException）
            // 从 cause 解出 SQLException 交给 mapper；解不出则按未知连接失败兜底
            Throwable root = e;
            while (root.getCause() != null) {
                if (root.getCause() instanceof SQLException) {
                    root = root.getCause();
                    break;
                }
                root = root.getCause();
            }
            if (root instanceof SQLException) {
                throw exceptionMapper.mapConnectionFailure((SQLException) root);
            }
            log.warn("[pool] getConnection failed (non-SQL cause): {}", e.getMessage());
            throw new DbException(
                    com.chaoxing.mcpserver.common.exception.ErrorCode.DB_CONNECTION_FAILED, e);
        }
    }
}
