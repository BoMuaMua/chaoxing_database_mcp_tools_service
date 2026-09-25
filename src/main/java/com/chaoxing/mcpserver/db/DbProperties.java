package com.chaoxing.mcpserver.db;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据库工具配置绑定。
 *
 * <pre>
 * mcp:
 *   db:
 *     readonly-enabled: true        # 只读查询工具总开关
 *     max-rows: 200                # 单次查询最大返回行数（防大结果集打爆 MCP 响应）
 *     statement-timeout-seconds: 30 # 单条语句超时（秒）
 * </pre>
 *
 * Bean 注册见 {@link com.chaoxing.mcpserver.mcp.McpDatabasePropertiesConfiguration}。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mcp.db")
public class DbProperties {

    /** 只读查询工具总开关；false 时 executeReadonlySql 直接拒绝。 */
    private boolean readonlyEnabled = true;

    /** 单次查询最大返回行数；超出截断并在结果中标注 truncated。默认 200。 */
    private int maxRows = 200;

    /** 单条语句超时（秒）；<=0 表示不限制。默认 30。 */
    private int statementTimeoutSeconds = 30;
}
