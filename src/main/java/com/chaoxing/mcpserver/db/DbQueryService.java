package com.chaoxing.mcpserver.db;

import com.chaoxing.mcpserver.common.exception.DbException;
import com.chaoxing.mcpserver.common.exception.DbExceptionMapper;
import com.chaoxing.mcpserver.common.exception.ErrorCode;
import com.chaoxing.mcpserver.config.pool.ConnectionProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读 SQL 查询服务（MCP 数据库工具的执行层）。
 * <p>
 * 通过 {@link ConnectionProvider}（config 包对外门面）获取连接执行 {@code SELECT}/{@code WITH}
 * 语句，结果返回结构化 Map（含 rowCount / truncated / data），由调用方按需序列化为
 * JSON 返回给 MCP 客户端。
 * </p>
 * <p>
 * 异常归一化：
 * <ul>
 *   <li>取连接失败 → {@code ConnectionProvider} 抛 {@link DbException}（1001/1002）；</li>
 *   <li>执行 SQL 的 {@code SQLException} → 本方法经 {@link DbExceptionMapper} 转
 *       {@link DbException}（1003 超时 / 1004 唯一键 / 1005 死锁 等），业务层只认
 *       {@code DbException}，不读 SQLState；</li>
 *   <li>SQL 不合法 → {@link UnsafeSqlException}（2001 只读防护拦截）。</li>
 * </ul>
 * </p>
 * <p>
 * 安全防护：
 * <ul>
 *   <li>执行前经 {@link ReadonlySqlGuard} 校验（单语句、只读、无危险关键字）；</li>
 *   <li>结果行数超过 {@code mcp.db.max-rows} 时截断并标注 {@code truncated=true}；</li>
 *   <li>{@code mcp.db.statement-timeout-seconds > 0} 时设置语句超时。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbQueryService {

    private final ConnectionProvider connectionProvider;
    private final DbExceptionMapper exceptionMapper;
    private final DbProperties properties;

    /**
     * 执行只读 SQL，返回结构化结果（data 为列名->值的有序行列表）。
     *
     * @param sql 原始 SQL（经 {@link ReadonlySqlGuard} 校验后才执行）
     * @return {@code Map{rowCount, truncated, data:[{col:val,...}]}}
     * @throws UnsafeSqlException 非安全 SQL（工具层拦截，code=2001）
     * @throws DbException        数据库执行 / 连接异常（已归一化，业务层只 catch 它）
     */
    public Map<String, Object> executeReadonly(String sql) {
        if (!properties.isReadonlyEnabled()) {
            throw new DbException(ErrorCode.TOOL_DISABLED, null);
        }
        String safeSql = ReadonlySqlGuard.validate(sql);
        log.debug("[db] executing readonly sql: {}", safeSql);

        int maxRows = properties.getMaxRows();
        int timeout = properties.getStatementTimeoutSeconds();

        try (Connection conn = connectionProvider.getConnection();
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
        } catch (java.sql.SQLException e) {
            // 统一归一化：业务层只 catch DbException，不读 SQLState
            throw exceptionMapper.map(e);
        }
    }
}
