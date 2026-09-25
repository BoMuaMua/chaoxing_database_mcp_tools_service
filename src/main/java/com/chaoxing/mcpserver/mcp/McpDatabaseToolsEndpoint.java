package com.chaoxing.mcpserver.mcp;

import com.chaoxing.mcpserver.common.exception.DbException;
import com.chaoxing.mcpserver.common.result.Result;
import com.chaoxing.mcpserver.db.DbQueryService;
import com.chaoxing.mcpserver.db.UnsafeSqlException;
import com.chaoxing.mcpserver.dbops.DatabaseOpsService;
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
 * <b>设计：单一端点 + 多个强类型工具方法</b>。端点仅一个，但端点下注册多个
 * {@link ToolMapping} 工具，每个操作独立强类型参数（MCP schema 友好，LLM 调用准确率高）：
 * <ul>
 *   <li>{@link #ping} —— 链路健康检查（占位）；</li>
 *   <li>{@link #executeReadonlySql} —— 原始只读 SQL 查询；</li>
 *   <li>{@link #insertRecord} —— 参数化 INSERT（写类，需 mcp.db.write-enabled=true）；</li>
 *   <li>{@link #updateRecord} —— 参数化 UPDATE（写类）；</li>
 *   <li>{@link #deleteRecord} —— 参数化 DELETE（写类）；</li>
 *   <li>{@link #selectRow} —— SELECT 单行；</li>
 *   <li>{@link #selectRows} —— SELECT 多行（截断标注）；</li>
 *   <li>{@link #countRows} —— COUNT(*) 计数；</li>
 *   <li>{@link #executeDdl} —— DDL（需 mcp.db.ddl-enabled=true）。</li>
 * </ul>
 * 全部工具底层统一走 {@link DatabaseOpsService}（强类型操作层）→
 * {@code JdbcExecutor}（SQL 执行底座）→ {@code ConnectionProvider}（连接门面），
 * 异常经 {@code DbExceptionMapper} 归一化为 {@link DbException}。
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
    private final DatabaseOpsService databaseOpsService;
    private final ObjectMapper objectMapper;

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
            return writeJson(Result.fail(
                    com.chaoxing.mcpserver.common.exception.ErrorCode.TOOL_UNSAFE_SQL.getCode(),
                    e.getMessage()));
        } catch (DbException e) {
            log.error("[db] readonly sql failed: code={}, safeMessage={}", e.getCode(), e.getSafeMessage());
            return writeJson(Result.fromDbException(e));
        }
    }

    // -----------------------------------------------------------------
    // 写类（参数化 INSERT / UPDATE / DELETE，需 mcp.db.write-enabled=true）
    // -----------------------------------------------------------------

    /**
     * 参数化 INSERT（返回自增主键）。
     *
     * @param table   目标表名
     * @param columns 列名列表（与 values 顺序对应）
     * @param values  值列表
     */
    @ToolMapping(description = "参数化 INSERT 一条记录（写类，需 mcp.db.write-enabled=true），返回自增主键")
    public String insertRecord(
            @Param(description = "目标表名") String table,
            @Param(description = "列名列表（顺序与 values 对应）") List<String> columns,
            @Param(description = "值列表（顺序与 columns 对应）") List<Object> values) {
        return callWriteResult(() -> databaseOpsService.insert(table, columns, values));
    }

    /**
     * 参数化 UPDATE（返回影响行数）。
     *
     * @param table    目标表名
     * @param set      要更新的列-&gt;值映射
     * @param whereSql WHERE 条件（不含 WHERE 关键字，仅条件，如 "id = ?"）
     * @param where    WHERE 占位符参数
     */
    @ToolMapping(description = "参数化 UPDATE 记录（写类，需 mcp.db.write-enabled=true），返回影响行数")
    public String updateRecord(
            @Param(description = "目标表名") String table,
            @Param(description = "要更新的列-值映射") Map<String, Object> set,
            @Param(description = "WHERE 条件子句（不含 WHERE 关键字，如 \"id = ?\"）") String whereSql,
            @Param(description = "WHERE 占位符参数（顺序与 whereSql 中 ? 对应）") List<Object> where) {
        return callWriteResult(() -> databaseOpsService.update(table, set, whereSql, whereToArray(where)));
    }

    /**
     * 参数化 DELETE（返回影响行数）。
     *
     * @param table    目标表名
     * @param whereSql WHERE 条件（不含 WHERE 关键字，必须提供以防全表清空）
     * @param where    WHERE 占位符参数
     */
    @ToolMapping(description = "参数化 DELETE 记录（写类，需 mcp.db.write-enabled=true），返回影响行数；必须提供 WHERE")
    public String deleteRecord(
            @Param(description = "目标表名") String table,
            @Param(description = "WHERE 条件子句（不含 WHERE 关键字，如 \"id = ?\"）") String whereSql,
            @Param(description = "WHERE 占位符参数（顺序与 whereSql 中 ? 对应）") List<Object> where) {
        return callWriteResult(() -> databaseOpsService.delete(table, whereSql, whereToArray(where)));
    }

    // -----------------------------------------------------------------
    // 读类（SELECT 单行 / 多行 / 计数）
    // -----------------------------------------------------------------

    /**
     * SELECT 单行（多列 Map；无结果返回 null）。
     */
    @ToolMapping(description = "SELECT 单行查询（只读，参数化占位符），返回单行 Map 或 null")
    public String selectRow(
            @Param(description = "只读 SQL（含 ? 占位符），如 SELECT * FROM users WHERE id = ?") String sql,
            @Param(description = "占位符参数（顺序与 SQL 中 ? 对应）") List<Object> args) {
        return callReadResult(() -> databaseOpsService.selectRow(sql, whereToArray(args)));
    }

    /**
     * SELECT 多行（截断标注）。
     */
    @ToolMapping(description = "SELECT 多行查询（只读，参数化占位符），结果截断标注 truncated")
    public String selectRows(
            @Param(description = "只读 SQL（含 ? 占位符），如 SELECT * FROM users LIMIT 100") String sql,
            @Param(description = "占位符参数（顺序与 SQL 中 ? 对应）") List<Object> args) {
        return callReadResult(() -> databaseOpsService.selectRows(sql, whereToArray(args)));
    }

    /**
     * COUNT(*) 计数。
     */
    @ToolMapping(description = "SELECT COUNT(*) 计数（只读，参数化占位符），返回计数值")
    public String countRows(
            @Param(description = "只读 SQL（含 COUNT(*)，如 SELECT COUNT(*) FROM users WHERE ...）") String sql,
            @Param(description = "占位符参数（顺序与 SQL 中 ? 对应）") List<Object> args) {
        return callReadResult(() -> databaseOpsService.count(sql, whereToArray(args)));
    }

    // -----------------------------------------------------------------
    // DDL（需 mcp.db.ddl-enabled=true）
    // -----------------------------------------------------------------

    /**
     * 执行 DDL（CREATE / ALTER / DROP）。
     */
    @ToolMapping(description = "执行 DDL（CREATE/ALTER/DROP，需 mcp.db.ddl-enabled=true）")
    public String executeDdl(@Param(description = "DDL 语句，如 CREATE TABLE ... / ALTER TABLE ... / DROP TABLE ...") String sql) {
        return callDdlResult(databaseOpsService, sql);
    }

    // -----------------------------------------------------------------
    // 内部辅助
    // -----------------------------------------------------------------

    /** 写类操作：统一捕获 DbException + UnsafeSqlException，Result 包装。 */
    private String callWriteResult(java.util.function.Supplier<Object> op) {
        try {
            return writeJson(Result.ok(op.get()));
        } catch (com.chaoxing.mcpserver.db.UnsafeSqlException e) {
            log.warn("[db] write op rejected: {}", e.getMessage());
            return writeJson(Result.fail(
                    com.chaoxing.mcpserver.common.exception.ErrorCode.TOOL_UNSAFE_SQL.getCode(), e.getMessage()));
        } catch (DbException e) {
            log.error("[db] write op failed: code={}, safeMessage={}", e.getCode(), e.getSafeMessage());
            return writeJson(Result.fromDbException(e));
        }
    }

    /** 读类操作（经 ReadonlySqlGuard）：统一捕获，Result 包装。 */
    private String callReadResult(java.util.function.Supplier<Object> op) {
        try {
            return writeJson(Result.ok(op.get()));
        } catch (com.chaoxing.mcpserver.db.UnsafeSqlException e) {
            log.warn("[db] read op rejected: {}", e.getMessage());
            return writeJson(Result.fail(
                    com.chaoxing.mcpserver.common.exception.ErrorCode.TOOL_UNSAFE_SQL.getCode(), e.getMessage()));
        } catch (DbException e) {
            log.error("[db] read op failed: code={}, safeMessage={}", e.getCode(), e.getSafeMessage());
            return writeJson(Result.fromDbException(e));
        }
    }

    /** DDL 操作：executeDdl 返回 void，需在 Supplier 里包成值再统一捕获，Result 包装。 */
    private String callDdlResult(DatabaseOpsService svc, String sql) {
        java.util.function.Supplier<Object> op = () -> {
            svc.executeDdl(sql);
            return "ok";
        };
        try {
            return writeJson(Result.ok(op.get()));
        } catch (DbException e) {
            log.error("[db] ddl failed: code={}, safeMessage={}", e.getCode(), e.getSafeMessage());
            return writeJson(Result.fromDbException(e));
        }
    }

    private static Object[] whereToArray(List<Object> list) {
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

