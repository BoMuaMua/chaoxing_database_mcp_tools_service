package com.chaoxing.mcpserver.auth.filter;

import com.chaoxing.mcpserver.auth.TokenExtractor;
import com.chaoxing.mcpserver.auth.TokenVerificationResult;
import com.chaoxing.mcpserver.auth.TokenVerifier;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;

/**
 * MCP 前置鉴权过滤器（Solon Filter 挂载点，先于 MCP 协议帧解析执行）。
 * <p>
 * 请求链：HTTP 请求 → <b>本过滤器（token 校验）</b> →
 * Solon 路由匹配 → MCP 端点（SSE / Streamable HTTP）→ MCP 工具方法。
 * 校验不通过时直接写 401/403 响应并终止链路，MCP 协议层完全不可见未授权请求。
 * </p>
 * <p>
 * 选择 Solon Filter 的原因：solon-ai-mcp 的端点是标准 Solon Web 路由，
 * Solon Filter 能在 MCP 协议帧解析之前完成校验；且本项目的 MCP 端点由
 * Solon 容器管理（{@code McpServerConfig} 中 {@code Solon.start()} 启动），
 * 过滤器通过 {@code app.router().filter(...)} 显式注册（Solon 侧禁用扫描，
 * 鉴权过滤器不依赖扫描范围），由 Spring 构造器注入的 {@code TokenVerifier}/
 * {@code TokenExtractor} 在 {@code McpServerConfig} 中手动传入。
 * </p>
 * <p>
 * 路径过滤：仅拦截 /mcp 前缀与 SSE 端点（/sse 前缀）；
 * 其余路径（健康检查、静态资源等）直接放行。
 * /mcp 下的 /message 回发端点（Streamable）不重复鉴权（SSE 连接建立时已验证）。
 * </p>
 */
@Slf4j
public class McpAuthFilter implements Filter {

    private final TokenVerifier tokenVerifier;
    private final TokenExtractor tokenExtractor;

    public McpAuthFilter(TokenVerifier tokenVerifier, TokenExtractor tokenExtractor) {
        this.tokenVerifier = tokenVerifier;
        this.tokenExtractor = tokenExtractor;
    }

    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        // 非 MCP 路径直接放行，不浪费校验
        if (!isMcpPath(ctx.pathNew())) {
            chain.doFilter(ctx);
            return;
        }

        // /mcp/**/message 端点（Streamable 回发）不重复鉴权
        if (ctx.pathNew().startsWith("/mcp/") && ctx.pathNew().endsWith("/message")) {
            chain.doFilter(ctx);
            return;
        }

        // 1. 提取 token
        String token = tokenExtractor.extract(ctx);

        // 2. 校验
        TokenVerificationResult result;
        try {
            result = tokenVerifier.verify(token, ctx);
        } catch (Exception e) {
            // 校验器自身异常按 500 处理，避免把校验器故障当成鉴权失败
            log.error("[auth] token verifier threw, rejecting request: path={}", ctx.pathNew(), e);
            ctx.status(500);
            ctx.output("{\"error\":\"internal_server_error\",\"message\":\"Token verification failed\"}");
            ctx.setHandled(true);
            return;
        }

        // 3. 失败 → 终止链路，返回 401/403
        if (result == null) {
            log.error("[auth] verifier returned null result, rejecting: path={}", ctx.pathNew());
            ctx.status(401);
            ctx.output("{\"error\":\"unauthorized\",\"message\":\"Verifier returned null\"}");
            ctx.setHandled(true);
            return;
        }
        if (!result.isPassed()) {
            log.warn("[auth] rejected request: path={}, status={}, reason={}, tokenPresent={}",
                    ctx.pathNew(), result.getHttpStatus(), result.getReason(),
                    token != null && !token.isBlank());
            ctx.status(result.getHttpStatus());
            ctx.output("{\"error\":\"" + (result.getHttpStatus() == 401 ? "unauthorized" : "forbidden")
                    + "\",\"message\":\"" + escapeJson(result.getReason()) + "\"}");
            ctx.setHandled(true);
            return;
        }

        // 4. 通过 → 放行进入 MCP 处理链
        if (result.getClaims() != null) {
            ctx.attrSet("mcp.auth.claims", result.getClaims());
        }
        log.debug("[auth] passed: path={}, tokenPresent={}", ctx.pathNew(), token != null && !token.isBlank());
        chain.doFilter(ctx);
    }

    /** 判断是否 MCP 请求路径（含 SSE 端点）。 */
    private boolean isMcpPath(String path) {
        if (path == null) {
            return false;
        }
        String p = path.startsWith("/") ? path : "/" + path;
        // Streamable HTTP 端点
        if (p.startsWith("/mcp/")) {
            return true;
        }
        // SSE 端点（solon-ai-mcp 默认路径）
        if (p.equals("/sse") || p.startsWith("/sse/")) {
            return true;
        }
        return false;
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
