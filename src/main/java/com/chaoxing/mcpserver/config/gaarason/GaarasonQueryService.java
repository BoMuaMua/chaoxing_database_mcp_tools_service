package com.chaoxing.mcpserver.config.gaarason;

import com.chaoxing.mcpserver.common.exception.DbException;
import com.chaoxing.mcpserver.common.exception.DbExceptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gaarason 原生 SQL 查询执行层（废弃旧 JDBC 直查底座，改用 Gaarason 数据源通道）。
 * <p>
 * 查询连接来源：注入 Gaarason 的 {@code GaarasonDataSource}（{@code @Primary}，
 * 继承 {@code javax.sql.DataSource}，内部包装 HikariCP 池做路由）。
 * 参数化 SQL 经 {@code Connection} + {@code PreparedStatement} 执行（{@code ?} 占位符防注入），
 * 结果集逐行转为 {@code Map<列名,值>}（保持列顺序，JSON 友好），
 * 异常经 {@link DbExceptionMapper} 归一化为 {@link DbException}（1001/1002/1003 等）。
 * </p>
 * <p>
 * <b>为什么用 Gaarason 数据源而非 JDBC 直连池</b>：
 * 连接统一走 Gaarason 路由数据源，将来 Gaarason 切多数据源/读写分离时查询自动跟随路由，
 * 业务零改动；取连接失败的 {@code SQLRuntimeException}（cause 包着底层 {@code SQLException}）
 * 也在此解 cause 链还原后交给 {@link DbExceptionMapper}，业务层不会拿到 Gaarason 原始异常。
 * </p>
 * <p>
 * 线程安全（无实例状态）。仅查询，无写类/DDL（写类需另加确认/白名单机制，不在基座默认放行）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GaarasonQueryService {

    /** Gaarason 路由数据源（{@code @Primary}，继承 javax.sql.DataSource）。 */
    private final gaarason.database.contract.connection.GaarasonDataSource gaarasonDataSource;
    private final DbExceptionMapper exceptionMapper;

    // -----------------------------------------------------------------
    // 查询
    // -----------------------------------------------------------------

    /**
     * 参数化查询（多列结果集，列名→值的有序行列表）。
     *
     * @param sql  带 {@code ?} 占位符的只读 SQL
     * @param args  占位符参数（顺序与 {@code ?} 对应）
     * @return 行列表（{@link LinkedHashMap} 保持列顺序；无结果返回空列表）
     */
    public List<Map<String, Object>> queryForMapList(String sql, Object... args) {
        log.debug("[gaarason] queryForMapList: {} | args: {}", sql, args);
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = getGaarasonConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        String columnName = meta.getColumnLabel(i);
                        if (columnName == null || columnName.isEmpty()) {
                            columnName = meta.getColumnName(i);
                        }
                        row.put(columnName, rs.getObject(i));
                    }
                    result.add(row);
                }
            }
        } catch (SQLException e) {
            throw exceptionMapper.map(e);
        }
        return result;
    }

    /**
     * 参数化查询（单行多列 Map；无结果返回 null）。
     */
    public Map<String, Object> queryForRow(String sql, Object... args) {
        List<Map<String, Object>> rows = queryForMapList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 参数化查询（单值，取第一行第一列；无结果返回 null）。
     */
    public <T> T queryForObject(String sql, Class<T> type, Object... args) {
        log.debug("[gaarason] queryForObject: {} | args: {}", sql, args);
        try (Connection conn = getGaarasonConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject(1, type);
                }
            }
            return null;
        } catch (SQLException e) {
            throw exceptionMapper.map(e);
        }
    }

    // -----------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------

    /**
     * 经 Gaarason 数据源取连接，统一把取连接失败归一化为 {@link DbException}。
     * <p>Gaarason 包装器取连接失败可能抛 {@code SQLRuntimeException}（cause 包着底层
     * {@code SQLException}），在此解 cause 链还原后交给 mapper；还原不出则按连接失败兜底。</p>
     */
    private Connection getGaarasonConnection() {
        try {
            Connection conn = gaarasonDataSource.getConnection();
            if (log.isDebugEnabled()) {
                log.debug("[gaarason] connection acquired: {}", conn);
            }
            return conn;
        } catch (SQLException e) {
            // Gaarason 透传 SQLException
            throw exceptionMapper.mapConnectionFailure(e);
        } catch (RuntimeException e) {
            // Gaarason 包装器取连接失败抛 SQLRuntimeException（cause = SQLException）
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
            log.warn("[gaarason] getConnection failed (non-SQL cause): {}", e.getMessage());
            throw new DbException(
                    com.chaoxing.mcpserver.common.exception.ErrorCode.DB_CONNECTION_FAILED, e);
        }
    }

    /** 绑定参数占位符。 */
    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }
}
