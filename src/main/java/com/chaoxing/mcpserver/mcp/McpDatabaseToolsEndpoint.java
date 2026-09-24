package com.chaoxing.mcpserver.mcp;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.IMcpServerEndpoint;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Param;
import org.springframework.stereotype.Service;

/**
 * 数据库工具 MCP 服务端点（首个端点，占位基座）。
 * <p>
 * 该 Bean 由 {@link McpServerConfig#springCom2Endpoint()} 在 Solon 容器中
 * 手动构建为 {@code McpServerEndpointProvider}，端点路径为
 * {@code /mcp/database-tools}（Streamable Stateless，集群友好）。
 * 后续数据库工具（查询/建表/迁移等）在此类下以 {@link ToolMapping} 方法扩展。
 * </p>
 */
@Service
@McpServerEndpoint(
        channel = McpChannel.STREAMABLE_STATELESS,
        name = "database-tools",
        mcpEndpoint = "/mcp/database-tools"
)
public class McpDatabaseToolsEndpoint implements IMcpServerEndpoint {

    /**
     * 占位工具：验证 MCP 节点可被调用。
     * 后续替换为真实数据库工具（如查询、DDL、迁移）。
     */
    @ToolMapping(description = "服务健康检查（占位工具，验证 MCP 链路贯通）")
    public String ping(@Param(description = "任意标识，原样返回") String marker) {
        return "pong: " + marker;
    }
}
