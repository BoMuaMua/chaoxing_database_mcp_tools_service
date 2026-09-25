package com.chaoxing.mcpserver.config.pool;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 连接池参数统一配置（HikariCP）。
 * <p>
 * 绑定 {@code spring.datasource.hikari.*}（Spring Boot 原生支持的 HikariCP 池参数前缀），
 * 把池参数（最大连接数、超时、空闲回收等）收敛到这里；
 * 业务代码只注入 {@link ConnectionProvider}（对外一个 {@code getConnection()} 方法），
 * 不直接读 {@code HikariDataSource} 或池参数。
 * </p>
 *
 * <pre>
 * spring:
 *   datasource:
 *     url: jdbc:mysql://...
 *     driver-class-name: com.mysql.cj.jdbc.Driver
 *     username: ...
 *     password: ...
 *     hikari:
 *       pool-name: McpDatabasePool
 *       maximum-pool-size: 10
 *       minimum-idle: 1
 *       connection-timeout: 5000
 *       idle-timeout: 300000
 *       max-lifetime: 1800000
 *       connection-test-query: "SELECT 1"
 *       initialization-fail-timeout: -1   # 占位池不阻塞启动
 * </pre>
 *
 * 本类仅作为参数读取的统一出口（将来加连接池监控、动态调参都从这里走），
 * 不实际创建 {@code HikariDataSource}（由 Spring Boot 自动配置负责）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "spring.datasource.hikari")
public class PoolProperties {

    /** 连接池名称（日志用）。 */
    private String poolName = "McpDatabasePool";

    /** 最大连接数。默认 10。 */
    private int maximumPoolSize = 10;

    /** 最小空闲连接。默认 1。 */
    private int minimumIdle = 1;

    /** 获取连接超时（毫秒），默认 5000（超出抛 1002 DB_POOL_EXHAUSTED）。 */
    private long connectionTimeout = 5000;

    /** 空闲连接回收时间（毫秒），默认 300000（5 分钟）。 */
    private long idleTimeout = 300_000;

    /** 连接最大寿命（毫秒），默认 1800000（30 分钟，防止 MySQL 8h 空闲断连）。 */
    private long maxLifetime = 1_800_000;

    /** 连接有效性检测 SQL，默认 SELECT 1。 */
    private String connectionTestQuery = "SELECT 1";

    /**
     * 池初始化失败超时（毫秒）。
     * -1 = 占位池不阻塞应用启动；真实接入后可改 5000（启动即暴露连接失败）。
     */
    private long initializationFailTimeout = -1;
}
