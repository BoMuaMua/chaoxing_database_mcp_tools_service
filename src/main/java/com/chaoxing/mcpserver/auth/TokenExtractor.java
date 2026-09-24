package com.chaoxing.mcpserver.auth;

import org.noear.solon.core.handle.Context;

/**
 * 从 MCP 请求中提取 token。
 * <p>
 * MCP 规范（Authorization 章节）要求授权信息通过 HTTP {@code Authorization: Bearer <token>}
 * 请求头传递；但 SSE 长连接通道下部分客户端习惯把 token 放在查询串里，
 * 因此本提取器同时支持两种渠道，优先级：
 * <ol>
 *   <li>{@code Authorization: Bearer xxx}（规范渠道）</li>
 *   <li>自定义请求头 {@code X-Auth-Token}（混跑场景的常用约定）</li>
 *   <li>查询串 {@code ?token=xxx}（SSE 通道降级渠道）</li>
 * </ol>
 * 提取不到返回 null，由 {@link TokenVerifier} 决定 null 时的行为。
 * </p>
 */
public interface TokenExtractor {

    /**
     * 从请求中提取 token。
     *
     * @param ctx 当前 Solon 请求上下文
     * @return 提取到的原始 token；未携带时返回 null（不返回空串，避免歧义）
     */
    String extract(Context ctx);
}
