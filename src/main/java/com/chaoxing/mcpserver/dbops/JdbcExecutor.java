package com.chaoxing.mcpserver.dbops;

import com.chaoxing.mcpserver.config.pool.ConnectionProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 轻量级 JDBC 执行器——数据库常用操作的统一 SQL 执行底座
 * （封装形式参考 jaravel-vendor 的 {@code JdbcExecutor}）。
 * <p>
 * <h3>设计</h3>
 * 替代 Spring {@code JdbcTemplate}：任何数据库操作（查询 / 增删改 / DDL）
 * 统一经由本底座完成，各操作方不各自维护一套
 * {@code Connection/PreparedStatement/ResultSet} 工具方法。
 * </p>
 * <p>
 * <h3>连接来源</h3>
 * 通过 {@link ConnectionProvider}（config 包对外门面）取连接，不直接读池参数；
 * 取连接失败由 {@code ConnectionProvider} 统一抛 {@code DbException}（1001/1002），
 * 本底座只处理 SQL 执行层的 {@link SQLException}（经调用方交给
 * {@code DbExceptionMapper} 归一化）。
 * </p>
 * <p>
 * <h3>封装的常用操作</h3>
 * {@link #execute}（DDL）、{@link #update}（参数化 DML，返回影响行数）、
 * {@link #insertReturningKey}（自增主键返回）、
 * {@link #queryForObject}（单值查询）、{@link #queryForList}（列表查询）、
 * {@link #queryForMapList}（多列结果集，列名-&gt;值有序行列表）、
 * {@link #queryMapped}（自定义行映射）。
 * 所有方法自动取/关连接，无需手动管理资源；线程安全（无实例状态）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JdbcExecutor {

    private final ConnectionProvider connectionProvider;

    // -----------------------------------------------------------------
    // DDL
    // -----------------------------------------------------------------

    /**
     * 执行 DDL 语句（CREATE TABLE / ALTER TABLE / DROP TABLE 等）。
     *
     * @param sql SQL 语句
     * @throws SQLException SQL 执行失败
     */
    public void execute(String sql) throws SQLException {
        log.debug("[dbops] execute: {}", sql);
        try (Connection conn = connectionProvider.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    // -----------------------------------------------------------------
    // DML（参数化）
    // -----------------------------------------------------------------

    /**
     * 执行参数化 UPDATE / INSERT / DELETE，返回受影响行数。
     *
     * @param sql  带 {@code ?} 占位符的 SQL
     * @param args 参数值
     * @return 受影响行数
     */
    public int update(String sql, Object... args) throws SQLException {
        log.debug("[dbops] update: {} | args: {}", sql, args);
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    /**
     * 执行 INSERT 并返回自增主键（JDBC {@code RETURN_GENERATED_KEYS}，
     * MySQL / PostgreSQL / H2 / SQLite / SQL Server / Oracle 均支持）。
     *
     * @param sql  带 {@code ?} 占位符的 INSERT 语句
     * @param args 参数值
     * @return 自增主键；取不到时返回 -1
     */
    public long insertReturningKey(String sql, Object... args) throws SQLException {
        log.debug("[dbops] insertReturningKey: {} | args: {}", sql, args);
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, args);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            return -1L;
        }
    }

    // -----------------------------------------------------------------
    // 查询
    // -----------------------------------------------------------------

    /**
     * 单值查询（取第一行第一列）。
     *
     * @param sql  带 {@code ?} 占位符的查询 SQL
     * @param args 参数值
     * @return 第一行第一列的值；无结果时返回 null
     */
    public <T> T queryForObject(String sql, Class<T> type, Object... args) throws SQLException {
        log.debug("[dbops] queryForObject: {} | args: {}", sql, args);
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject(1, type);
                }
            }
            return null;
        }
    }

    /**
     * 列表查询（取第一列所有行）。
     *
     * @param sql  带 {@code ?} 占位符的查询 SQL
     * @param args 参数值
     * @return 值列表
     */
    public List<String> queryForList(String sql, Object... args) throws SQLException {
        log.debug("[dbops] queryForList: {} | args: {}", sql, args);
        List<String> values = new ArrayList<>();
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
            }
        }
        return values;
    }

    /**
     * 多列结果集查询（列名-&gt;值的有序行列表，供 MCP 工具 JSON 化返回）。
     * <p>列名优先取 {@code columnLabel}（别名），空则回退 {@code columnName}。</p>
     *
     * @param sql  带 {@code ?} 占位符的查询 SQL
     * @param args 参数值
     * @return 行列表（{@link LinkedHashMap} 保持列顺序）
     */
    public List<Map<String, Object>> queryForMapList(String sql, Object... args) throws SQLException {
        log.debug("[dbops] queryForMapList: {} | args: {}", sql, args);
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = connectionProvider.getConnection();
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
        }
        return result;
    }

    /**
     * 自定义行映射查询（每行经 {@code mapper} 转为目标类型）。
     *
     * @param sql     带 {@code ?} 占位符的查询 SQL
     * @param args    参数值
     * @param mapper  行映射函数（ResultSet-&gt;T），允许抛受检异常
     * @return 映射后的结果列表
     */
    public <T> List<T> queryMapped(String sql, RowMapper<T> mapper, Object... args) throws SQLException {
        log.debug("[dbops] queryMapped: {}", sql);
        List<T> result = new ArrayList<>();
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapper.map(rs));
                }
            }
        }
        return result;
    }

    // -----------------------------------------------------------------
    // 辅助
    // -----------------------------------------------------------------

    /** 行映射函数：允许抛出受检 {@link SQLException}。 */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** 绑定参数占位符。 */
    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }
}
