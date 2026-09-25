package com.chaoxing.mcpserver.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读 SQL 查询服务（MCP 数据库工具的执行层）。
 * <p>
 * 基于 {@code javax.sql.DataSource}（由 Gaarason starter 注册）直接获取连接执行
 * {@code SELECT}/{@code WITH} 语句，结果返回结构化 Map（含 rowCount / truncated / data），
 * 由调用方按需序列化为 JSON 返回给 MCP 客户端。
 * </p>
 * <p>
 * 安全防护：
 * <ul>
 *   <li>执行前经 {@link ReadonlySqlGuard} 校验（单语句、只读、无危险关键字）；</li>
 *   <li>结果行数超过 {@code mcp.db.max-rows} 时截断并标注 {@code truncated=true}；</li>
 *   <li>{@code mcp.db.statement-timeout-seconds > 0} 时设置语句超时；</li>
 *   <li>数据库异常向上抛给工具方法转结构化错误（不吞异常）。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbQueryService {

    private final DataSourceProvider dataSourceProvider;
    private final DbProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 执行只读 SQL，返回结构化结果（data 为列名->值的有序行列表）。
     *
     * @param sql 原始 SQL（经 {@link ReadonlySqlGuard} 校验后才执行）
     * @return {@code Map{rowCount, truncated, data:[{col:val,...}]}}
     * @throws UnsafeSqlException 非安全 SQL
     * @throws SQLException       数据库执行异常
     */
    public Map<String, Object> executeReadonly(String sql) throws SQLException {
        if (!properties.isReadonlyEnabled()) {
            throw new SQLException("readonly db tool is disabled (mcp.db.readonly-enabled=false)");
        }
        String safeSql = ReadonlySqlGuard.validate(sql);
        log.debug("[db] executing readonly sql: {}", safeSql);

        int maxRows = properties.getMaxRows();
        int timeout = properties.getStatementTimeoutSeconds();

        try (Connection conn = dataSourceProvider.getDataSource().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(maxRows + 1); // 多取一行判断是否截断
            if (timeout > 0) {
                stmt.setQueryTimeout(timeout);
            }
            try (ResultSet rs = stmt.executeQuery(safeSql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<Map<String, Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() == maxRows) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("rowCount", rows.size());
                result.put("truncated", truncated);
                result.put("data", rows);
                log.debug("[db] readonly sql ok: rows={}, truncated={}", rows.size(), truncated);
                return result;
            }
        }
    }

    /** 将结构化结果序列化为 JSON 字符串（供工具方法直接返回）。 */
    public String toJson(Map<String, Object> result) throws SQLException {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new SQLException("failed to serialize db result: " + e.getMessage(), e);
        }
    }
}
