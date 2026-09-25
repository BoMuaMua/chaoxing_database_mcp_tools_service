package com.chaoxing.mcpserver.mcp;

import com.chaoxing.mcpserver.common.exception.DbException;
import com.chaoxing.mcpserver.common.result.Result;
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
    private final ObjectMapper objectMapper;

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
     * @return {@link Result} 的 JSON 字符串（MCP 工具方法返回 String，调用方解析）
     */
    @ToolMapping(description = "执行只读 SQL 查询（仅限 SELECT/WITH，单语句，结果 JSON 化；超出行数截断）")
    public String executeReadonlySql(@Param(description = "只读 SQL 语句，如 SELECT ... FROM ...") String sql) {
        try {
            var result = dbQueryService.executeReadonly(sql);
            // 成功：Result 包装后序列化（MCP 工具方法返回 String）
            return writeJson(Result.ok(result));
        } catch (UnsafeSqlException e) {
            log.warn("[db] readonly sql rejected: sql={}, reason={}", sql, e.getMessage());
            return writeJson(Result.fail(
                    com.chaoxing.mcpserver.common.exception.ErrorCode.TOOL_UNSAFE_SQL.getCode(),
                    e.getMessage()));
        } catch (DbException e) {
            // 业务层只 catch DbException（不读 SQLState）
            log.error("[db] readonly sql failed: code={}, safeMessage={}", e.getCode(), e.getSafeMessage());
            return writeJson(Result.fromDbException(e));
        }
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
