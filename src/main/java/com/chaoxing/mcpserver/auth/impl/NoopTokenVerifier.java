package com.chaoxing.mcpserver.auth.impl;

import com.chaoxing.mcpserver.auth.TokenVerificationResult;
import com.chaoxing.mcpserver.auth.TokenVerifier;
import com.chaoxing.mcpserver.auth.config.AuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.core.handle.Context;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

/**
 * 空置默认 Token 校验实现。
 * <p>
 * 甲方尚未给出 token 的加密/签名校验逻辑，本实现按 {@code mcp.auth.mode} 工作：
 * </p>
 * <ul>
 *   <li><b>pass</b>（默认）：放行所有请求；请求未携带 token 时打 WARN 提醒当前处于非安全模式。</li>
 *   <li><b>deny</b>：拒绝所有请求（联调前的灰度封禁）。</li>
 *   <li><b>custom</b>：委托给 {@code mcp.auth.custom-verifier-bean} 指定的 Spring Bean
 *       （甲方逻辑落地后，新增 {@link TokenVerifier} 实现类并在此切换，本类不再改动）。</li>
 * </ul>
 * <p>
 * 日志约定：校验失败打 WARN，通过打 DEBUG；任何日志都不记录 token 明文。
 * </p>
 */
@Slf4j
public class NoopTokenVerifier implements TokenVerifier {

    private final AuthProperties properties;
    private final BeanFactory beanFactory;

    public NoopTokenVerifier(AuthProperties properties, BeanFactory beanFactory) {
        this.properties = properties;
        this.beanFactory = beanFactory;
    }

    @Override
    public TokenVerificationResult verify(String token, Context ctx) {
        String path = ctx.pathNew();
        switch (properties.getAuthMode()) {
            case PASS:
                if (token == null || token.isBlank()) {
                    log.warn("[auth] mode=PASS but request carries no token, allowing through: path={}", path);
                }
                return TokenVerificationResult.pass();
            case DENY:
                log.warn("[auth] mode=DENY, rejecting request: path={}", path);
                return TokenVerificationResult.unauthorized("Auth mode=DENY: all requests are rejected until a verifier is configured");
            case CUSTOM:
                TokenVerifier custom = resolveCustomVerifier();
                if (custom == null) {
                    log.error("[auth] mode=CUSTOM but no custom verifier bean available (mcp.auth.custom-verifier-bean='{}'), rejecting: path={}",
                            properties.getCustomVerifierBean(), path);
                    return TokenVerificationResult.unauthorized("Auth mode=CUSTOM configured but no custom verifier is registered");
                }
                return custom.verify(token, ctx);
            default:
                return TokenVerificationResult.forbidden("Unknown auth mode: " + properties.getMode());
        }
    }

    /** 按 {@code mcp.auth.custom-verifier-bean} 的 Bean 名解析自定义校验器；找不到返回 null。 */
    private TokenVerifier resolveCustomVerifier() {
        String beanName = properties.getCustomVerifierBean();
        if (beanName == null || beanName.isBlank()) {
            return null;
        }
        try {
            // 仅当该 Bean 是 TokenVerifier 实现时才委托，避免误注入非校验器 Bean
            if (beanFactory.isTypeMatch(beanName, TokenVerifier.class)) {
                return beanFactory.getBean(beanName, TokenVerifier.class);
            }
            log.warn("[auth] bean '{}' is not a TokenVerifier, rejecting", beanName);
            return null;
        } catch (NoSuchBeanDefinitionException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "NoopTokenVerifier(mode=" + properties.getMode() + ")";
    }
}
