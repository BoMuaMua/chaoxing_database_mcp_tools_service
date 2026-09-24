package com.chaoxing.mcpserver.mcp;

import com.chaoxing.mcpserver.auth.TokenExtractor;
import com.chaoxing.mcpserver.auth.TokenVerifier;
import com.chaoxing.mcpserver.auth.filter.McpAuthFilter;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.Solon;
import org.noear.solon.ai.chat.prompt.MethodPromptProvider;
import org.noear.solon.ai.chat.resource.MethodResourceProvider;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.mcp.server.IMcpServerEndpoint;
import org.noear.solon.ai.mcp.server.McpServerEndpointProvider;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.web.servlet.SolonServletFilter;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.ToolSchemaUtil;
import org.noear.solon.ai.util.ParamDesc;

import java.lang.reflect.Parameter;
import java.util.List;

/**
 * Solon MCP 端点的启动装配（对齐官方示例 solon-ai-in-springboot3 架构，适配 Solon 3.9.5）。
 * <p>
 * 请求链：HTTP → Spring Tomcat →
 * {@code SolonServletFilter}（{@code FilterRegistrationBean} 桥接 /mcp/*、/sse/*）→
 * Solon 容器 FilterChain（含 {@link McpAuthFilter}）→ MCP 端点（{@code @McpServerEndpoint} Bean）。
 * 鉴权由 {@link McpAuthFilter} 在 Solon FilterChain 最外层完成，先于 MCP 协议帧解析，
 * 校验不通过时直接写 401/403 并终止链路。
 * </p>
 * <ul>
 *   <li>{@link #start()} 在 Spring 容器就绪后启动 Solon AppContext（SPI 加载
 *       {@code McpPlugin}），把 Spring 容器里带 {@code @McpServerEndpoint} 注解的
 *       {@link IMcpServerEndpoint} Bean 手动构建为 {@link McpServerEndpointProvider}；</li>
 *   <li>鉴权过滤器 {@link McpAuthFilter} 通过 {@code app.router().filter(...)} 显式注册
 *       （Solon 侧 {@code enableScanning(false)}，鉴权过滤器不依赖扫描范围），
 *       其 {@code TokenVerifier}/{@code TokenExtractor} 由 Spring 构造器注入；</li>
 *   <li>{@link #stop()} 随 Spring 容器销毁而停止 Solon。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class McpServerConfig {

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    private final List<IMcpServerEndpoint> serverEndpoints;
    private final TokenVerifier tokenVerifier;
    private final TokenExtractor tokenExtractor;

    public McpServerConfig(List<IMcpServerEndpoint> serverEndpoints,
                           TokenVerifier tokenVerifier,
                           TokenExtractor tokenExtractor) {
        this.serverEndpoints = serverEndpoints;
        this.tokenVerifier = tokenVerifier;
        this.tokenExtractor = tokenExtractor;
    }

    @PostConstruct
    public void start() {
        // 支持 Spring @RequestBody / @RequestParam 注解作为 MCP 工具参数描述
        ToolSchemaUtil.addBodyDetector(e -> e.isAnnotationPresent(RequestBody.class));
        ToolSchemaUtil.addParamResolver((e, t) -> {
            RequestParam p1Anno = e.getAnnotation(RequestParam.class);
            if (p1Anno != null) {
                Parameter p1 = (Parameter) e;
                String name = Utils.annoAlias(p1Anno.name(), p1.getName());
                return new ParamDesc(name, t.getGenericType(), p1Anno.required(), "", p1Anno.defaultValue());
            }
            return null;
        });

        System.setProperty("server.contextPath", contextPath);

        // 启动 Solon 容器：McpPlugin（SPI）随之加载，构建 MCP 端点 provider；
        // 鉴权过滤器显式注册，不依赖 Solon 扫描范围
        Solon.start(McpServerConfig.class, new String[]{}, app -> {
            app.enableScanning(false); // Spring 已管理组件，禁用 Solon 扫描
            app.router().filter(new McpAuthFilter(tokenVerifier, tokenExtractor));
        });
        log.info("[mcp] solon app started, auth filter registered (mcp.auth via Spring beans)");

        // Spring 组件转为 MCP 端点
        springCom2Endpoint();
    }

    @PreDestroy
    public void stop() {
        if (Solon.app() != null) {
            Solon.stopBlock(false, Solon.cfg().stopDelay());
        }
    }

    /** 收集 Spring 容器里带 {@code @McpServerEndpoint} 的 Bean，手动构建端点 provider。 */
    protected void springCom2Endpoint() {
        for (IMcpServerEndpoint serverEndpoint : serverEndpoints) {
            Class<?> serverEndpointClz = AopUtils.getTargetClass(serverEndpoint);
            McpServerEndpoint anno = AnnotationUtils.findAnnotation(serverEndpointClz, McpServerEndpoint.class);

            if (anno == null) {
                continue;
            }

            McpServerEndpointProvider serverEndpointProvider = McpServerEndpointProvider.builder()
                    .from(serverEndpointClz, anno)
                    .build();

            serverEndpointProvider.addTool(new MethodToolProvider(serverEndpointClz, serverEndpoint));
            serverEndpointProvider.addResource(new MethodResourceProvider(serverEndpointClz, serverEndpoint));
            serverEndpointProvider.addPrompt(new MethodPromptProvider(serverEndpointClz, serverEndpoint));

            serverEndpointProvider.postStart();
            log.info("[mcp] endpoint registered: {} -> {}", anno.name(), anno.mcpEndpoint());
        }
    }

    /** 将 MCP / SSE 路径桥接到 Solon 容器（McpAuthFilter 在 Solon FilterChain 内执行，先于端点处理）。 */
    @Bean
    public FilterRegistrationBean<SolonServletFilter> mcpServerFilter() {
        FilterRegistrationBean<SolonServletFilter> filter = new FilterRegistrationBean<>();
        filter.setName("SolonFilter");
        filter.addUrlPatterns("/mcp/*", "/sse/*");
        filter.setFilter(new SolonServletFilter());
        return filter;
    }
}
