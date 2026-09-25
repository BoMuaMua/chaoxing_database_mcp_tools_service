package com.chaoxing.mcpserver.mcp;

import com.chaoxing.mcpserver.db.DbProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据库工具的配置与 Bean 装配。
 * <ul>
 *   <li>{@link DbProperties} 绑定 {@code mcp.db.*}（readonly-enabled / max-rows /
 *       statement-timeout-seconds）；</li>
 *   <li>{@link ObjectMapper} 在容器缺失时兜底提供（Spring Boot web 栈自带
 *       自动配置的 ObjectMapper 时，本 Bean 因 {@link ConditionalOnMissingBean} 不生效）。</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(DbProperties.class)
public class McpDatabasePropertiesConfiguration {

    /** 兜底 ObjectMapper：容器已有（spring-boot-starter-web 自动配置）时不重复定义。 */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper mcpObjectMapper() {
        return new ObjectMapper();
    }
}
