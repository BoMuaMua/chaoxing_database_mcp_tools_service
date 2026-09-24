package com.chaoxing.mcpserver.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Auth 框架配置绑定（调用方鉴权）。
 *
 * <pre>
 * mcp.auth:
 *   mode: pass            # pass | deny | custom
 *   custom-verifier-bean: ""   # mode=custom 时，指定 TokenVerifier Bean 名
 * </pre>
 *
 * Bean 的注册（{@code @Component}）统一由
 * {@link com.chaoxing.mcpserver.auth.config.AuthConfiguration} 完成，
 * 本类只做配置绑定，避免双注册。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mcp.auth")
public class AuthProperties {

    /**
     * 校验模式：{@code pass}（空置放行，默认） / {@code deny}（空置拒绝） /
     * {@code custom}（委托 {@link #customVerifierBean} 指定的实现）。
     * 支持配置为小写或大写，由 {@link AuthMode#fromValue(String)} 解析。
     */
    private String mode = "pass";

    /**
     * mode=custom 时生效：目标 {@link com.chaoxing.mcpserver.auth.TokenVerifier} Bean 的名称。
     * 甲方给出加密/签名校验逻辑后，新增实现类并在此指向它。
     */
    private String customVerifierBean = "";

    /** 获取解析后的模式枚举。 */
    public AuthMode getAuthMode() {
        return AuthMode.fromValue(this.mode);
    }
}
