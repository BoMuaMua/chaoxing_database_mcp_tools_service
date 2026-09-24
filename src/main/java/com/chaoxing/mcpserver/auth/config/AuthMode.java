package com.chaoxing.mcpserver.auth.config;

/**
 * Token 校验模式。
 * <ul>
 *   <li>{@link #PASS}   —— 空置放行：当前默认值。甲方加密逻辑未到位，任何请求都放行，
 *                           但缺失 token 时打 WARN 日志提醒处于非安全状态。</li>
 *   <li>{@link #DENY}   —— 空置拒绝：用于联调前的灰度封禁，任何请求都以 401 拒绝。</li>
 *   <li>{@link #CUSTOM} —— 自定义：委托给 {@code mcp.auth.custom-verifier-bean}
 *                           指定的 {@link com.chaoxing.mcpserver.auth.TokenVerifier} Bean
 *                           （甲方给出加密/签名逻辑后落地这里）。</li>
 * </ul>
 */
public enum AuthMode {
    PASS,
    DENY,
    CUSTOM;

    /** 大小写不敏感解析，非法值抛 {@link IllegalArgumentException}。 */
    public static AuthMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("mcp.auth.mode must be one of: pass, deny, custom");
        }
        return valueOf(value.trim().toUpperCase());
    }
}
