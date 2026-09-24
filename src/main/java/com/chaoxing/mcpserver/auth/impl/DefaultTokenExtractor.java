package com.chaoxing.mcpserver.auth.impl;

import com.chaoxing.mcpserver.auth.TokenExtractor;
import org.noear.solon.core.handle.Context;

/**
 * 默认 token 提取实现。
 * <p>
 * 提取渠道与优先级：
 * <ol>
 *   <li>{@code Authorization: Bearer xxx} —— MCP 规范要求的渠道，也是首选；</li>
 *   <li>请求头 {@code X-Auth-Token} —— 混跑场景常用的约定头；</li>
 *   <li>查询串 {@code ?token=xxx} —— SSE 长连接通道的降级渠道。</li>
 * </ol>
 * 任一渠道命中即返回，未命中返回 {@code null}（不返回空串，避免歧义）。
 * </p>
 */
public class DefaultTokenExtractor implements TokenExtractor {

    /** 规范渠道：Bearer 前缀。 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 约定渠道：自定义请求头。 */
    private static final String AUTH_TOKEN_HEADER = "X-Auth-Token";

    /** 降级渠道：查询串参数名。 */
    private static final String TOKEN_QUERY_PARAM = "token";

    @Override
    public String extract(Context ctx) {
        String bearer = extractBearer(ctx);
        if (bearer != null) {
            return bearer;
        }
        String header = trimToNull(ctx.header(AUTH_TOKEN_HEADER));
        if (header != null) {
            return header;
        }
        return trimToNull(ctx.param(TOKEN_QUERY_PARAM));
    }

    /** 解析 {@code Authorization: Bearer xxx}，xxx 非空才有效；否则返回 null。 */
    private static String extractBearer(Context ctx) {
        String auth = ctx.header("Authorization");
        if (auth == null || auth.isBlank()) {
            return null;
        }
        String trimmed = auth.trim();
        if (trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return trimToNull(trimmed.substring(BEARER_PREFIX.length()));
        }
        // 兼容未带 Bearer 前缀但直接放 token 的调用方（MCP 规范不推荐，但现实存在）
        return trimToNull(trimmed);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
