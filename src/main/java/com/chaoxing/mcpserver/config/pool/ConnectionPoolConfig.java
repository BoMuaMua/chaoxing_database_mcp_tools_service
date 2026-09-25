package com.chaoxing.mcpserver.config.pool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 连接池统一配置（HikariCP）。
 * <p>
 * <b>设计决策</b>：不手动定义 {@code @Primary DataSource} Bean——
 * Spring Boot 的 {@code DataSourceAutoConfiguration} 已按 {@code spring.datasource.*}
 * 自动装配唯一的 {@code HikariDataSource}；Gaarason starter 的
 * {@code gaarasonDataSource}（{@code GaarasonDataSource}，{@code @Primary}）
 * 通过 {@code ObjectProvider<DataSource>} 包装它做路由。
 * 两者类型不同（{@code HikariDataSource} vs {@code GaarasonDataSource}），不冲突。
 * </p>
 * <p>
 * 本类职责：
 * <ul>
 *   <li>装配 {@link PoolProperties}（绑定 {@code spring.datasource.hikari.*}），
 *       作为池参数读取的<b>统一出口</b>（将来加连接池监控、动态调参都从这里走）；</li>
 *   <li>启动日志打印池配置摘要，便于联调核对。</li>
 * </ul>
 * </p>
 * <p>
 * 业务方"获取连接"的唯一入口是 {@link ConnectionProvider}（注入精确类型
 * {@code HikariDataSource}，不读池参数）。
 * </p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(PoolProperties.class)
public class ConnectionPoolConfig {

    /**
     * 启动时打印池配置摘要（不创建 Bean，仅日志）。
     * 池参数由 Spring Boot 的 {@code HikariDataSourceProperties} 绑定到
     * {@code spring.datasource.hikari.*}，本 Bean 不干预其装配。
     */
    public void onStartup(PoolProperties poolProperties) {
        log.info("[pool] HikariCP config summary: pool={}, maxPoolSize={}, minIdle={}, "
                        + "connTimeout={}ms, idleTimeout={}ms, maxLifetime={}ms, initFailTimeout={}ms",
                poolProperties.getPoolName(),
                poolProperties.getMaximumPoolSize(),
                poolProperties.getMinimumIdle(),
                poolProperties.getConnectionTimeout(),
                poolProperties.getIdleTimeout(),
                poolProperties.getMaxLifetime(),
                poolProperties.getInitializationFailTimeout());
    }
}
