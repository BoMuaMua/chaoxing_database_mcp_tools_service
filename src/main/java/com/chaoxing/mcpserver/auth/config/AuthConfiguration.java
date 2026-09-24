package com.chaoxing.mcpserver.auth.config;

import com.chaoxing.mcpserver.auth.TokenExtractor;
import com.chaoxing.mcpserver.auth.TokenVerifier;
import com.chaoxing.mcpserver.auth.impl.DefaultTokenExtractor;
import com.chaoxing.mcpserver.auth.impl.NoopTokenVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auth 框架的 Bean 装配。
 * <p>
 * Spring Boot 的启动类位于 {@code com.chaoxing.mcpserver}，
 * 组件扫描默认覆盖 {@code com.chaoxing.mcpserver.**}，
 * 因此 {@code com.chaoxing.mcpserver.auth.**} 下的类都会被扫到。
 * </p>
 * <p>
 * <b>挂载层</b>：{@code McpAuthFilter}（Solon Filter）不在本类注册——
 * 它由 {@code McpServerConfig} 在 {@code Solon.start()} 时通过
 * {@code app.router().filter(...)} 显式注册，
 * 本类只提供其依赖的 {@code TokenVerifier}/{@code TokenExtractor} Bean。
 * </p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {

    /**
     * 默认 token 提取器：Bearer 头 → X-Auth-Token 头 → ?token= 查询串。
     * 后续可新增实现并按需替换。
     */
    @Bean
    public TokenExtractor tokenExtractor() {
        return new DefaultTokenExtractor();
    }

    /**
     * 空置默认校验器（按 {@code mcp.auth.mode} 在 pass / deny / custom 之间工作）。
     * 甲方给出加密/签名逻辑后：新增实现类并配置
     * {@code mcp.auth.mode=custom} + {@code mcp.auth.custom-verifier-bean}，
     * 本 Bean 本身保持不变。
     */
    @Bean
    public TokenVerifier tokenVerifier(AuthProperties authProperties,
                                       BeanFactory beanFactory) {
        NoopTokenVerifier verifier = new NoopTokenVerifier(authProperties, beanFactory);
        log.info("[auth] token verifier ready: {}", verifier);
        return verifier;
    }
}
