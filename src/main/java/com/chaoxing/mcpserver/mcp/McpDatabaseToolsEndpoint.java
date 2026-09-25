package com.chaoxing.mcpserver.mcp;

import com.chaoxing.mcpserver.common.exception.DbException;
import com.chaoxing.mcpserver.common.exception.ErrorCode;
import com.chaoxing.mcpserver.common.result.Result;
import com.chaoxing.mcpserver.config.gaarason.GaarasonQueryTools;
import com.chaoxing.mcpserver.db.DbQueryService;
import com.chaoxing.mcpserver.db.UnsafeSqlException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.IMcpServerEndpoint;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Param;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 数据库工具 MCP 服务端点（首个端点）。
 * <p>
 * 该 Bean 由 {@link McpServerConfig#springCom2Endpoint()} 在 Solon 容器中
 * 手动构建为 {@code McpServerEndpointProvider}，端点路径为
 * {@code /mcp/database-tools}（Streamable Stateless，集群友好）。
 * </p>
 * <p>
 * <b>设计：单一端点 + 多个强类型查询工具方法</b>（纯查询，无写类/DDL）。端点仅一个，
 * 端点下注册多个 {@link ToolMapping} 工具，每个查询操作独立强类型参数（MCP schema 友好，
 * LLM 调用准确率高）：
 * <ul>
 *   <li>{@link #ping} —— 链路健康检查（占位）；</li>
 *   <li>{@link #executeReadonlySql} —— 原始只读 SQL 查询；</li>
 *   <li>{@link #selectRow} —— SELECT 单行；</li>
 *   <li>{@link #selectRows} —— SELECT 多行（截断标注）；</li>
 *   <li>{@link #countRows} —— COUNT(*) 计数；</li>
 *   <li>{@link #distinctQuery} —— 去重查询（SELECT DISTINCT）；</li>
 *   <li>{@link #groupByQuery} —— 分组聚合查询（GROUP BY）。</li>
 * </ul>
 * 全部工具底层统一走 {@link GaarasonQueryTools}（强类型查询层）→
 * {@code GaarasonQueryService}（Gaarason 数据源通道）→ {@code DbExceptionMapper}（异常归一化），
 * 结果经 {@link Result} 包装序列化为 JSON 返回 MCP 客户端。
 * </p>
 * <p>
 * <b>可扩展</b>：新增查询类型 = 在 {@link GaarasonQueryTools} 加一个强类型方法 +
 * 在此端点加一个 {@code @ToolMapping}，底层统一走 Gaarason，零工厂零注册表。
 * 写类/DDL 工具需另加确认/白名单机制，不在基座默认放行。
 * </p>
 * <p>
 * 响应统一走 {@link Result} 包装：成功 {@code {success:true,code:0,data:...}}；
 * 失败 {@code {success:false,code:10xx/20xx/40xx,message:...}}。
 * 工具方法捕获 {@link DbException}（不读 SQLState），序列化 {@link Result#toMap()} 返回。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@McpServerEndpoint(
        channel = McpChannel.STREAMABLE_STATELESS,
        name = "database-tools",
        mcpEndpoint = "/mcp/database-tools"
)
public class McpDatabaseToolsEndpoint implements IMcpServerEndpoint {

    private final DbQueryService dbQueryService;
    private final GaarasonQueryTools gaarasonQueryTools;
    private final ObjectMapper objectMapper;

    // -----------------------------------------------------------------
    // 占位 / 原始 SQL
    // -----------------------------------------------------------------

    /**
     * 占位工具：验证 MCP 节点可被调用。
     */
    @ToolMapping(description = "服务健康检查（验证 MCP 链路贯通），原样返回传入标识")
    public String ping(@Param(description = "任意标识，原样返回") String marker) {
        return "pong: " + marker;
    }

    /**
     * 原始只读 SQL 查询工具（仅限 SELECT/WITH，单语句）。
     */
    @ToolMapping(description = "执行只读 SQL 查询（仅限 SELECT/WITH，单语句，结果 JSON 化；超出行数截断）")
    public String executeReadonlySql(@Param(description = "只读 SQL 语句，如 SELECT ... FROM ...") String sql) {
        try {
            var result = dbQueryService.executeReadonly(sql);
            return writeJson(Result.ok(result));
        } catch (UnsafeSqlException e) {
            log.warn("[db] readonly sql rejected: sql={}, reason={}", sql, e.getMessage());
            return writeJson(Result.fail(ErrorCode.TOOL_UNSAFE_SQL.getCode(), e.getMessage()));
        } catch (DbException e) {
            log.error("[db] readonly sql failed: code={}, safeMessage={}", e.getCode(), e.getSafeMessage());
            return writeJson(Result.fromDbException(e));
        }
    }

    // -----------------------------------------------------------------
    // 结构化查询（走 Gaarason 数据源通道）
    // -----------------------------------------------------------------

    /**
     * SELECT 单行（多列 Map；无结果返回 null）。
     */
    @ToolMapping(description = "SELECT 单行查询（只读，参数化占位符，走 Gaarason 数据源），返回单行 Map 或 null")
    public String selectRow(
            @Param(description = "只读 SQL（含 ? 占位符），如 SELECT * FROM users WHERE id = ?") String sql,
            @Param(description = "占位符参数（顺序与 SQL 中 ? 对应）") List<Object> args) {
        return callQueryResult(() -> gaarasonQueryTools.selectRow(sql, toArray(args)));
    }

    /**
     * SELECT 多行（截断标注）。
     */
    @ToolMapping(description = "SELECT 多行查询（只读，参数化占位符，走 Gaarason 数据源），结果截断标注 truncated")
    public String selectRows(
            @Param(description = "只读 SQL（含 ? 占位符），如 SELECT * FROM users LIMIT 100") String sql,
            @Param(description = "占位符参数（顺序与 SQL 中 ? 对应）") List<Object> args) {
        return callQueryResult(() -> gaarasonQueryTools.selectRows(sql, toArray(args)));
    }

    /**
     * COUNT(*) 计数。
     */
    @ToolMapping(description = "SELECT COUNT(*) 计数（只读，参数化占位符，走 Gaarason 数据源），返回计数值")
    public String countRows(
            @Param(description = "只读 SQL（含 COUNT(*)，如 SELECT COUNT(*) FROM users WHERE ...）") String sql,
            @Param(description = "占位符参数（顺序与 SQL 中 ? 对应）") List<Object> args) {
        return callQueryResult(() -> gaarasonQueryTools.count(sql, toArray(args)));
    }

    /**
     * 去重查询（SELECT DISTINCT，参数化）。
     */
    @ToolMapping(description = "SELECT DISTINCT 去重查询（只读，走 Gaarason 数据源），可带过滤条件")
    public String distinctQuery(
            @Param(description = "目标表名") String table,
            @Param(description = "去重列名列表（为空则 DISTINCT 全部列）") List<String> columns,
            @Param(description = "WHERE 过滤条件（不含 WHERE 关键字，如 \"status = ?\"；为空则不过滤）") String filters,
            @Param(description = "过滤条件占位符参数（顺序与 filters 中 ? 对应）") List<Object> args) {
        return callQueryResult(() -> gaarasonQueryTools.distinct(table, columns, filters, toArray(args)));
    }

    /**
     * 分组聚合查询（SELECT ... GROUP BY，参数化）。
     */
    @ToolMapping(description = "SELECT ... GROUP BY 分组聚合查询（只读，走 Gaarason 数据源），可带 HAVING 过滤")
    public String groupByQuery(
            @Param(description = "目标表名") String table,
            @Param(description = "投影表达式（含聚合函数，如 \"status, COUNT(*) AS cnt\"）") String selectExpr,
            @Param(description = "分组列（逗号分隔，如 \"status\"）") String groupByExpr,
            @Param(description = "HAVING 过滤条件（不含 HAVING 关键字，可选，如 \"cnt > ?\"）") String filters,
            @Param(description = "HAVING 占位符参数（顺序与 filters 中 ? 对应）") List<Object> args) {
        return callQueryResult(() -> gaarasonQueryTools.groupBy(table, selectExpr, groupByExpr, filters, toArray(args)));
    }

    // -----------------------------------------------------------------
    // 内部辅助
    // -----------------------------------------------------------------

    /** 查询操作：统一捕获 DbException + UnsafeSqlException，Result 包装。 */
    private String callQueryResult(java.util.function.Supplier<Object> op) {
        try {
            return writeJson(Result.ok(op.get()));
        } catch (UnsafeSqlException e) {
            log.warn("[gaarason] query rejected: {}", e.getMessage());
            return writeJson(Result.fail(ErrorCode.TOOL_UNSAFE_SQL.getCode(), e.getMessage()));
        } catch (DbException e) {
            log.error("[gaarason] query failed: code={}, safeMessage={}", e.getCode(), e.getSafeMessage());
            return writeJson(Result.fromDbException(e));
        }
    }

    private static Object[] toArray(List<Object> list) {
        return list == null ? new Object[0] : list.toArray();
    }

    /** 统一序列化 Result → JSON 字符串（MCP 工具方法返回 String；吞掉序列化异常，保证永不抛出）。 */
    private String writeJson(Result<?> result) {
        try {
            return objectMapper.writeValueAsString(result.toMap());
        } catch (Exception e) {
            log.error("[mcp] failed to serialize Result", e);
            return "{\"success\":false,\"code\":-1,\"message\":\"internal serialization error\"}";
        }
    }
}
