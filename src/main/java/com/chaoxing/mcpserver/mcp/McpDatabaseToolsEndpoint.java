package com.chaoxing.mcpserver.mcp;

import com.chaoxing.mcpserver.db.DbQueryService;
import com.chaoxing.mcpserver.db.UnsafeSqlException;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.IMcpServerEndpoint;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Param;
import org.springframework.stereotype.Service;

import java.sql.SQLException;

/**
 * 数据库工具 MCP 服务端点（首个端点）。
 * <p>
 * 该 Bean 由 {@link McpServerConfig#springCom2Endpoint()} 在 Solon 容器中
 * 手动构建为 {@code McpServerEndpointProvider}，端点路径为
 * {@code /mcp/database-tools}（Streamable Stateless，集群友好）。
 * </p>
 * <p>
 * 工具（{@link ToolMapping} 方法）：
 * <ul>
 *   <li>{@link #ping} —— 链路健康检查（占位，验证 MCP 贯通）；</li>
 *   <li>{@link #executeReadonlySql} —— 安全只读 SQL 查询（第一个真实数据库工具）。</li>
 * </ul>
 * 后续数据库工具（写操作、DDL、迁移等）在此类下扩展；
 * 写类工具需另加确认/白名单机制，不在本端点默认放行。
 * </p>
 */
@Slf4j
@Service
@McpServerEndpoint(
        channel = McpChannel.STREAMABLE_STATELESS,
        name = "database-tools",
        mcpEndpoint = "/mcp/database-tools"
)
public class McpDatabaseToolsEndpoint implements IMcpServerEndpoint {

    private final DbQueryService dbQueryService;

    public McpDatabaseToolsEndpoint(DbQueryService dbQueryService) {
        this.dbQueryService = dbQueryService;
    }

    /**
     * 占位工具：验证 MCP 节点可被调用。
     */
    @ToolMapping(description = "服务健康检查（验证 MCP 链路贯通），原样返回传入标识")
    public String ping(@Param(description = "任意标识，原样返回") String marker) {
        return "pong: " + marker;
    }

    /**
     * 第一个真实数据库工具：安全只读 SQL 查询。
     * <p>
     * 仅允许单条 {@code SELECT}/{@code WITH} 语句；经 {@code ReadonlySqlGuard}
     * 校验后通过 {@link DbQueryService} 执行，结果序列化为 JSON 返回。
     * 行数超过 {@code mcp.db.max-rows} 时截断并标注 {@code truncated=true}。
     * </p>
     *
     * @param sql 只读 SQL（如 {@code SELECT id, name FROM users WHERE id = 1}）
     * @return JSON 字符串：成功为 {@code {"rowCount":N,"truncated":bool,"data":[{...}]}}；
     *         失败为 {@code {"error":"...","message":"..."}}（不抛异常给 MCP 层）
     */
    @ToolMapping(description = "执行只读 SQL 查询（仅限 SELECT/WITH，单语句，结果 JSON 化；超出行数截断）")
    public String executeReadonlySql(@Param(description = "只读 SQL 语句，如 SELECT ... FROM ...") String sql) {
        try {
            var result = dbQueryService.executeReadonly(sql);
            return dbQueryService.toJson(result);
        } catch (UnsafeSqlException e) {
            log.warn("[db] readonly sql rejected: sql={}, reason={}", sql, e.getMessage());
            return "{\"error\":\"unsafe_sql\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}";
        } catch (SQLException e) {
            log.error("[db] readonly sql failed: sql={}", sql, e);
            return "{\"error\":\"db_error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }
}
