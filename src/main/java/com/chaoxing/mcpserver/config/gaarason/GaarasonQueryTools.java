package com.chaoxing.mcpserver.config.gaarason;

import com.chaoxing.mcpserver.common.exception.DbException;
import com.chaoxing.mcpserver.common.exception.ErrorCode;
import com.chaoxing.mcpserver.db.DbProperties;
import com.chaoxing.mcpserver.db.ReadonlySqlGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gaarason 强类型查询工具层（MCP 工具方法底层）。
 * <p>
 * 全部查询走 {@link GaarasonQueryService}（Gaarason 数据源通道），纯查询无写类；
 * SELECT 类操作经 {@link ReadonlySqlGuard} 防护（单语句、只读、无危险关键字），
 * 结果行数受 {@code mcp.db.max-rows} 截断保护。
 * </p>
 * <p>
 * <b>可扩展</b>：新增查询类型 = 在 Service 加一个强类型方法 + 端点加一个
 * {@code @ToolMapping}，底层统一走 {@link GaarasonQueryService}，零工厂零注册表。
 * </p>
 *
 * <h3>查询方法清单</h3>
 * <table>
 *   <tr><th>方法</th><th>操作</th><th>返回</th></tr>
 *   <tr><td>{@link #selectRows}</td><td>多行查询（截断标注）</td><td>{rowCount,truncated,data}</td></tr>
 *   <tr><td>{@link #selectRow}</td><td>单行查询</td><td>行 Map 或 null</td></tr>
 *   <tr><td>{@link #count}</td><td>COUNT(*) 计数</td><td>计数值</td></tr>
 *   <tr><td>{@link #distinct}</td><td>去重查询</td><td>行列表</td></tr>
 *   <tr><td>{@link #groupBy}</td><td>分组聚合查询</td><td>行列表</td></tr>
 * </table>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GaarasonQueryTools {

    private final GaarasonQueryService gaarasonQueryService;
    private final DbProperties properties;

    // -----------------------------------------------------------------
    // 查询
    // -----------------------------------------------------------------

    /**
     * SELECT 多行（超过 {@code mcp.db.max-rows} 截断并标注 truncated）。
     *
     * @param sql  只读 SQL（经 {@link ReadonlySqlGuard} 校验，含 {@code ?} 占位符）
     * @param args 占位符参数
     * @return {@code Map{rowCount, truncated, data:[{col:val,...}]}}
     */
    public Map<String, Object> selectRows(String sql, Object... args) {
        requireReadonlyEnabled();
        String safeSql = ReadonlySqlGuard.validate(sql);
        log.debug("[gaarason] selectRows: {}", safeSql);
        int maxRows = properties.getMaxRows();
        List<Map<String, Object>> all = gaarasonQueryService.queryForMapList(safeSql, args);
        boolean truncated = all.size() > maxRows;
        List<Map<String, Object>> limited = truncated ? all.subList(0, maxRows) : all;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowCount", limited.size());
        result.put("truncated", truncated);
        result.put("data", limited);
        return result;
    }

    /**
     * SELECT 单行（取第一条结果，多列 Map；无结果返回 null）。
     */
    public Map<String, Object> selectRow(String sql, Object... args) {
        requireReadonlyEnabled();
        String safeSql = ReadonlySqlGuard.validate(sql);
        log.debug("[gaarason] selectRow: {}", safeSql);
        return gaarasonQueryService.queryForRow(safeSql, args);
    }

    /**
     * SELECT COUNT(*) 计数。
     *
     * @param sql  只读 SQL（含 COUNT(*)，如 {@code SELECT COUNT(*) FROM users WHERE ...}）
     * @return 计数值（无结果返回 0）
     */
    public long count(String sql, Object... args) {
        requireReadonlyEnabled();
        String safeSql = ReadonlySqlGuard.validate(sql);
        log.debug("[gaarason] count: {}", safeSql);
        Long v = gaarasonQueryService.queryForObject(safeSql, Long.class, args);
        return v == null ? 0L : v;
    }

    /**
     * 去重查询（SELECT DISTINCT）。
     * <p>{@code columns} 为去重列；{@code filters} 为额外过滤条件（空串则不过滤）；
     * 结果行数受 {@code mcp.db.max-rows} 截断。</p>
     *
     * @param table    目标表名
     * @param columns  去重列名列表（为空则 SELECT * 但加 DISTINCT 全部列；通常显式指定）
     * @param filters  过滤条件子句（不含 WHERE 关键字，仅条件，如 {@code status = ?}；空则不过滤）
     * @param args     过滤条件占位符参数
     * @return {@code Map{rowCount, truncated, data:[{col:val,...}]}}
     */
    public Map<String, Object> distinct(String table, List<String> columns, String filters, Object... args) {
        requireReadonlyEnabled();
        StringBuilder sql = new StringBuilder("SELECT DISTINCT ");
        if (columns == null || columns.isEmpty()) {
            sql.append("*");
        } else {
            List<String> cols = new ArrayList<>(columns);
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append(cols.get(i));
            }
        }
        sql.append(" FROM ").append(table);
        if (filters != null && !filters.isBlank()) {
            sql.append(" WHERE ").append(filters.trim());
        }
        String safeSql = ReadonlySqlGuard.validate(sql.toString());
        log.debug("[gaarason] distinct: {}", safeSql);
        int maxRows = properties.getMaxRows();
        List<Map<String, Object>> all = gaarasonQueryService.queryForMapList(safeSql, args);
        boolean truncated = all.size() > maxRows;
        List<Map<String, Object>> limited = truncated ? all.subList(0, maxRows) : all;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowCount", limited.size());
        result.put("truncated", truncated);
        result.put("data", limited);
        return result;
    }

    /**
     * 分组聚合查询（SELECT ... GROUP BY，可带聚合函数）。
     * <p>{@code selectExpr} 为投影表达式（含聚合函数，如 {@code status, COUNT(*) AS cnt}）；
     * {@code groupByExpr} 为分组列；{@code filters} 为 HAVING/过滤条件（可选）。</p>
     *
     * @param table       目标表名
     * @param selectExpr  投影表达式（含聚合函数）
     * @param groupByExpr 分组列（逗号分隔）
     * @param filters     WHERE 过滤条件（不含 WHERE 关键字，可选）
     * @param args        占位符参数
     * @return {@code Map{rowCount, truncated, data:[{col:val,...}]}}
     */
    public Map<String, Object> groupBy(String table, String selectExpr, String groupByExpr, String filters, Object... args) {
        requireReadonlyEnabled();
        if (selectExpr == null || selectExpr.isBlank() || groupByExpr == null || groupByExpr.isBlank()) {
            throw new DbException(ErrorCode.TOOL_UNSAFE_SQL.getCode(),
                    "groupBy requires non-empty selectExpr and groupByExpr", null);
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(selectExpr.trim()).append(" FROM ").append(table).append(" GROUP BY ").append(groupByExpr.trim());
        if (filters != null && !filters.isBlank()) {
            sql.append(" HAVING ").append(filters.trim());
        }
        String safeSql = ReadonlySqlGuard.validate(sql.toString());
        log.debug("[gaarason] groupBy: {}", safeSql);
        int maxRows = properties.getMaxRows();
        List<Map<String, Object>> all = gaarasonQueryService.queryForMapList(safeSql, args);
        boolean truncated = all.size() > maxRows;
        List<Map<String, Object>> limited = truncated ? all.subList(0, maxRows) : all;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowCount", limited.size());
        result.put("truncated", truncated);
        result.put("data", limited);
        return result;
    }

    // -----------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------

    private void requireReadonlyEnabled() {
        if (!properties.isReadonlyEnabled()) {
            throw new DbException(ErrorCode.TOOL_DISABLED.getCode(),
                    "readonly tools are disabled (mcp.db.readonly-enabled=false)", null);
        }
    }
}
