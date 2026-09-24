package com.chaoxing.mcpserver.auth;

import org.noear.solon.core.handle.Context;

/**
 * Token 校验核心接口（调用方鉴权）。
 * <p>
 * 本接口的职责是<b>校验调用方凭证（调用方身份/合法性）</b>：
 * 判断"谁有权调用这个 MCP 服务"，保护 MCP 端点不被任意第三方直接访问。
 * 后续所有 AI/MCP 请求在进入 MCP 节点（工具调用链路）之前，
 * 必须先由 {@link #verify(String, Context)} 完成校验。
 * </p>
 * <p>
 * <b>职责边界</b>：MCP 协议无法识别终端用户是谁（用户身份只能由智能体
 * 通过参数/请求头透传，如 {@code @Header("user")} 或工具参数中的 {@code userId}），
 * 该层面的用户级授权/审计属于工具层职责，不在本包范围内。
 * </p>
 * <p>
 * 甲方给出加密/签名校验逻辑后，只需新增一个实现类并切换配置
 * （{@code mcp.auth.mode=custom}），挂载层（{@code McpAuthFilter}）不需要任何改动。
 * </p>
 */
public interface TokenVerifier {

    /**
     * 校验请求携带的调用方 token 是否合法。
     *
     * @param token 从请求中提取的原始 token（可能为 null，表示未携带）
     * @param ctx   当前 Solon 请求上下文（预留扩展点：实现方将来可读取 IP、方法、路径等元数据）
     * @return 校验结果，永远不应返回 null
     */
    TokenVerificationResult verify(String token, Context ctx);
}
