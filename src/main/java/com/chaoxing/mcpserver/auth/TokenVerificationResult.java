package com.chaoxing.mcpserver.auth;

import java.util.Map;
import java.util.Objects;

/**
 * Token 校验结果（调用方鉴权）。
 * <p>
 * 不返回布尔值的原因：失败时必须携带失败原因（写入日志与响应体）以及期望的
 * HTTP 状态码（401 / 403），布尔值无法表达这些语义。
 * </p>
 * <p>
 * {@link #claims} 为预留扩展点：校验成功后可携带解析出的调用方声明
 * （如 clientName / 权限范围），由过滤器放入请求属性
 * （{@code ctx.attrSet("mcp.auth.claims", claims)}），供 MCP 工具方法按需读取。
 * 注意：claims 承载的是<b>调用方身份</b>，不是终端用户身份（用户身份由智能体
 * 在工具参数/请求头中透传，属于工具层职责）。
 * </p>
 */
public final class TokenVerificationResult {

    private final boolean passed;
    private final int httpStatus;
    private final String reason;
    private final Map<String, Object> claims;

    private TokenVerificationResult(boolean passed, int httpStatus, String reason, Map<String, Object> claims) {
        this.passed = passed;
        this.httpStatus = httpStatus;
        this.reason = reason;
        this.claims = claims;
    }

    /** 校验通过（无声明）。 */
    public static TokenVerificationResult pass() {
        return new TokenVerificationResult(true, 200, null, null);
    }

    /** 校验通过，并携带解析出的调用方声明（预留扩展点）。 */
    public static TokenVerificationResult pass(Map<String, Object> claims) {
        return new TokenVerificationResult(true, 200, null, claims);
    }

    /**
     * 未授权：token 缺失、格式错误，或校验失败且无法确定调用方身份。
     *
     * @param reason 失败原因（会出现在 WARN 日志与 401 响应体中；禁止包含 token 明文）
     */
    public static TokenVerificationResult unauthorized(String reason) {
        return new TokenVerificationResult(false, 401, reason, null);
    }

    /**
     * 禁止访问：token 存在且能确定身份，但校验逻辑判定无权访问。
     *
     * @param reason 失败原因（会出现在 WARN 日志与 403 响应体中；禁止包含 token 明文）
     */
    public static TokenVerificationResult forbidden(String reason) {
        return new TokenVerificationResult(false, 403, reason, null);
    }

    public boolean isPassed() {
        return passed;
    }

    /** 失败时应返回的 HTTP 状态码；校验通过时无意义。 */
    public int getHttpStatus() {
        return httpStatus;
    }

    /** 失败原因；校验通过时为 null。 */
    public String getReason() {
        return reason;
    }

    /** 校验成功时携带的调用方声明；无则为 null。 */
    public Map<String, Object> getClaims() {
        return claims;
    }

    @Override
    public String toString() {
        if (passed) {
            return "PASS" + (claims != null ? "(claims=" + claims.size() + ")" : "");
        }
        return "FAIL(status=" + httpStatus + ", reason=" + Objects.toString(reason, "-") + ")";
    }
}
