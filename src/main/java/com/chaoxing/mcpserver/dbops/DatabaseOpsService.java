package com.chaoxing.mcpserver.dbops;

import com.chaoxing.mcpserver.common.exception.DbException;
import com.chaoxing.mcpserver.common.exception.DbExceptionMapper;
import com.chaoxing.mcpserver.common.exception.ErrorCode;
import com.chaoxing.mcpserver.db.DbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * 数据库常用操作服务（强类型参数，MCP 工具层）。
 * <p>
 * 挂同一个 MCP 端点（{@code /mcp/database-tools}）下的多个强类型工具方法，
 * 底层统一经 {@link JdbcExecutor}（SQL 执行底座）+ {@code ConnectionProvider}
 * （连接门面）执行；异常统一经 {@link DbExceptionMapper} 归一化为
 * {@link DbException}，业务层只认 {@code DbException}，不读 SQLState。
 * </p>
 *
 * <h3>操作清单</h3>
 * <table>
 *   <tr><th>方法</th><th>操作</th><th>返回</th><th>安全开关</th></tr>
 *   <tr><td>{@link #insert}</td><td>INSERT（参数化，返回自增主键）</td><td>主键</td><td>mcp.db.write-enabled</td></tr>
 *   <tr><td>{@link #update}</td><td>UPDATE（参数化，返回影响行数）</td><td>行数</td><td>mcp.db.write-enabled</td></tr>
 *   <tr><td>{@link #delete}</td><td>DELETE（参数化，返回影响行数）</td><td>行数</td><td>mcp.db.write-enabled</td></tr>
 *   <tr><td>{@link #selectRow}</td><td>SELECT 单行（多列 Map）</td><td>行 Map</td><td>mcp.db.readonly-enabled</td></tr>
 *   <tr><td>{@link #selectRows}</td><td>SELECT 多行（截断标注）</td><td>{rowCount,truncated,data}</td><td>mcp.db.readonly-enabled</td></tr>
 *   <tr><td>{@link #count}</td><td>SELECT COUNT(*) 计数</td><td>计数值</td><td>mcp.db.readonly-enabled</td></tr>
 *   <tr><td>{@link #executeDdl}</td><td>DDL（CREATE/ALTER/DROP）</td><td>ok</td><td>mcp.db.ddl-enabled</td></tr>
 * </table>
 *
 * <h3>安全护栏</h3>
 * <ul>
 *   <li>写类 / DDL 默认关闭，需配置显式开启；未开启时抛 {@code TOOL_DISABLED}（2002）；</li>
 *   <li>所有参数化操作用 {@code ?} 占位符，业务不拼 SQL 字符串（防注入）；</li>
 *   <li>SELECT 仍经 {@code ReadonlySqlGuard} 防护（单语句、无危险关键字）；</li>
 *   <li>结果行数受 {@code mcp.db.max-rows} 截断保护。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseOpsService {

    private final JdbcExecutor jdbcExecutor;
    private final DbExceptionMapper exceptionMapper;
    private final DbProperties properties;

    // -----------------------------------------------------------------
    // 写类（参数化 INSERT / UPDATE / DELETE）
    // -----------------------------------------------------------------

    /**
     * 参数化 INSERT，返回自增主键。
     *
     * @param table    目标表名
     * @param columns  列名列表（与 values 顺序对应）
     * @param values   值列表
     * @return 自增主键（取不到时 -1）
     */
    public long insert(String table, List<String> columns, List<Object> values) {
        requireWriteEnabled();
        if (columns == null || values == null || columns.size() != values.size()) {
            throw new DbException(ErrorCode.TOOL_UNSAFE_SQL.getCode(),
                    "columns and values must be same-size lists", null);
        }
        String sql = buildInsert(table, columns);
        log.debug("[dbops] insert: table={}, cols={}, rows={}", table, columns, values.size());
        try {
            return jdbcExecutor.insertReturningKey(sql, values.toArray());
        } catch (SQLException e) {
            throw exceptionMapper.map(e);
        }
    }

    /**
     * 参数化 UPDATE，返回影响行数。
     *
     * @param table    目标表名
     * @param set      要更新的列-&gt;值映射
     * @param where    WHERE 条件占位符参数（与 whereClause 中 ? 数量对应）
     * @param whereSql WHERE 子句（不含 WHERE 关键字，仅条件，如 {@code id = ? AND status = ?}）
     * @return 受影响行数
     */
    public int update(String table, Map<String, Object> set, String whereSql, Object... where) {
        requireWriteEnabled();
        if (set == null || set.isEmpty()) {
            throw new DbException(ErrorCode.TOOL_UNSAFE_SQL.getCode(),
                    "set must be non-empty", null);
        }
        StringBuilder sql = new StringBuilder("UPDATE ");
        sql.append(table).append(" SET ");
        List<Object> args = new java.util.ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Object> e : set.entrySet()) {
            if (!first) sql.append(", ");
            sql.append(e.getKey()).append(" = ?");
            args.add(e.getValue());
            first = false;
        }
        if (whereSql != null && !whereSql.isBlank()) {
            sql.append(" WHERE ").append(whereSql.trim());
            for (Object a : where) args.add(a);
        }
        String fullSql = sql.toString();
        log.debug("[dbops] update: {}", fullSql);
        try {
            return jdbcExecutor.update(fullSql, args.toArray());
        } catch (SQLException e) {
            throw exceptionMapper.map(e);
        }
    }

    /**
     * 参数化 DELETE，返回影响行数。
     *
     * @param table    目标表名
     * @param whereSql WHERE 子句（不含 WHERE 关键字，如 {@code id = ?}）
     * @param where    WHERE 条件占位符参数
     * @return 受影响行数
     */
    public int delete(String table, String whereSql, Object... where) {
        requireWriteEnabled();
        if (whereSql == null || whereSql.isBlank()) {
            throw new DbException(ErrorCode.TOOL_UNSAFE_SQL.getCode(),
                    "delete requires a WHERE clause to avoid full-table wipes", null);
        }
        String sql = "DELETE FROM " + table + " WHERE " + whereSql.trim();
        log.debug("[dbops] delete: {}", sql);
        try {
            return jdbcExecutor.update(sql, where);
        } catch (SQLException e) {
            throw exceptionMapper.map(e);
        }
    }

    // -----------------------------------------------------------------
    // 读类（SELECT 单行 / 多行 / 计数）
    // -----------------------------------------------------------------

    /**
     * SELECT 单行（取第一条结果，多列 Map；无结果返回 null）。
     */
    public Map<String, Object> selectRow(String sql, Object... args) {
        requireReadonlyEnabled();
        String safeSql = com.chaoxing.mcpserver.db.ReadonlySqlGuard.validate(sql);
        log.debug("[dbops] selectRow: {}", safeSql);
        try {
            List<Map<String, Object>> rows = jdbcExecutor.queryForMapList(safeSql, args);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (SQLException e) {
            throw exceptionMapper.map(e);
        }
    }

    /**
     * SELECT 多行（超过 {@code mcp.db.max-rows} 截断并标注 truncated）。
     */
    public Map<String, Object> selectRows(String sql, Object... args) {
        requireReadonlyEnabled();
        String safeSql = com.chaoxing.mcpserver.db.ReadonlySqlGuard.validate(sql);
        log.debug("[dbops] selectRows: {}", safeSql);
        int maxRows = properties.getMaxRows();
        try {
            List<Map<String, Object>> all = jdbcExecutor.queryForMapList(safeSql, args);
            boolean truncated = all.size() > maxRows;
            List<Map<String, Object>> limited = truncated ? all.subList(0, maxRows) : all;
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("rowCount", limited.size());
            result.put("truncated", truncated);
            result.put("data", limited);
            return result;
        } catch (SQLException e) {
            throw exceptionMapper.map(e);
        }
    }

    /**
     * SELECT COUNT(*) 计数。
     *
     * @param sql    只读 SQL（含 COUNT(*)，如 {@code SELECT COUNT(*) FROM users WHERE ...}）
     * @return 计数值（无结果返回 0）
     */
    public long count(String sql, Object... args) {
        requireReadonlyEnabled();
        String safeSql = com.chaoxing.mcpserver.db.ReadonlySqlGuard.validate(sql);
        log.debug("[dbops] count: {}", safeSql);
        try {
            Long v = jdbcExecutor.queryForObject(safeSql, Long.class, args);
            return v == null ? 0L : v;
        } catch (SQLException e) {
            throw exceptionMapper.map(e);
        }
    }

    // -----------------------------------------------------------------
    // DDL
    // -----------------------------------------------------------------

    /**
     * 执行 DDL（CREATE / ALTER / DROP 等），仅 {@code mcp.db.ddl-enabled=true} 时放行。
     */
    public void executeDdl(String sql) {
        if (!properties.isDdlEnabled()) {
            throw new DbException(ErrorCode.TOOL_DISABLED.getCode(),
                    "ddl tools are disabled (mcp.db.ddl-enabled=false)", null);
        }
        log.debug("[dbops] ddl: {}", sql);
        try {
            jdbcExecutor.execute(sql);
        } catch (SQLException e) {
            throw exceptionMapper.map(e);
        }
    }

    // -----------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------

    private void requireWriteEnabled() {
        if (!properties.isWriteEnabled()) {
            throw new DbException(ErrorCode.TOOL_DISABLED.getCode(),
                    "write operations are disabled (mcp.db.write-enabled=false)", null);
        }
    }

    private void requireReadonlyEnabled() {
        if (!properties.isReadonlyEnabled()) {
            throw new DbException(ErrorCode.TOOL_DISABLED.getCode(),
                    "readonly tools are disabled (mcp.db.readonly-enabled=false)", null);
        }
    }

    private static String buildInsert(String table, List<String> columns) {
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(table).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(columns.get(i));
        }
        sql.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("?");
        }
        sql.append(")");
        return sql.toString();
    }
}
