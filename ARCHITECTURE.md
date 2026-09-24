# MCP 基座 — 架构落地说明

## 一、整体架构（Spring Boot + Solon 双容器混跑）

```
HTTP 请求
  │
  ▼
Spring Tomcat（spring-boot-starter-web，端口 8080）
  │
  └─ SolonServletFilter（Servlet 层，FilterRegistrationBean 桥接 /mcp/*、/sse/*）
        将请求转发给 Solon 容器
  │
  ▼
Solon AppContext（McpServerConfig @PostConstruct 中 Solon.start() 启动，enableScanning(false)）
  │
  ├─ McpPlugin（SPI 插件，solon-ai-mcp）
  │
  └─ FilterChain（含 McpAuthFilter）→ 路由匹配 → MCP 端点
        McpAuthFilter（Solon Filter，由 app.router().filter() 显式注册）
        先于 MCP 协议帧解析执行：token 校验不通过直接写 401/403 并终止链路
  │
  ▼
McpServerEndpointProvider（McpServerConfig.springCom2Endpoint() 手动构建）
  收集 Spring 容器里 @McpServerEndpoint 注解的 IMcpServerEndpoint Bean
  │
  ▼
MCP 端点（@McpServerEndpoint + @ToolMapping 方法）
```

## 二、关键文件

| 文件 | 职责 |
|---|---|
| `mcp/McpServerConfig.java` | Spring @Configuration；@PostConstruct 中 Solon.start() + app.router().filter(McpAuthFilter) + 手动构建端点 provider；@PreDestroy 停止 Solon；FilterRegistrationBean 注册 SolonServletFilter |
| `mcp/McpDatabaseToolsEndpoint.java` | 首个 MCP 端点占位（ping 工具），后续在此类扩展数据库工具 |
| `auth/filter/McpAuthFilter.java` | Solon Filter（org.noear.solon.core.handle.Filter），拦截 /mcp/*、/sse/*，TokenVerifier 校验 |
| `auth/TokenVerifier.java` | 校验接口 `verify(String token, Context ctx)`，扩展点 |
| `auth/TokenExtractor.java` | 提取接口 `extract(Context ctx)` |
| `auth/impl/NoopTokenVerifier.java` | 空置默认实现，按 mcp.auth.mode（pass/deny/custom）工作 |
| `auth/impl/DefaultTokenExtractor.java` | 默认提取器：Bearer 头 → X-Auth-Token 头 → ?token= 查询串 |
| `auth/config/AuthConfiguration.java` | Bean 装配（AuthProperties + TokenExtractor + TokenVerifier），不注册过滤器 |
| `auth/config/AuthProperties.java` | @ConfigurationProperties 绑定 mcp.auth.*（由 @EnableConfigurationProperties 启用） |

## 三、配置契约

```yaml
# application.yml
mcp:
  auth:
    mode: pass            # pass（默认）| deny（灰度封禁）| custom（甲方逻辑落地后切这里）
    custom-verifier-bean: ""   # mode=custom 时，指定 TokenVerifier Bean 名
```

## 四、甲方加密逻辑落地路径

1. 新增 `TokenVerifier` 实现类（如 `JwtTokenVerifier`），实现 `verify(String, Context)`
2. 在 `AuthConfiguration` 中注册为 Bean
3. `application.yml` 改为 `mode: custom` + `custom-verifier-bean: jwtTokenVerifier`
4. `McpAuthFilter` 与 `McpServerConfig` 完全不需要修改

## 五、端点调用方式

MCP 客户端（Streamable Stateless）：

```
POST http://host:8080/mcp/database-tools
Content-Type: application/json
Accept: application/json, text/event-stream
Authorization: Bearer <token>

{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
```

工具调用：

```
POST http://host:8080/mcp/database-tools
{
  "jsonrpc":"2.0","id":2,"method":"tools/call",
  "params":{"name":"ping","arguments":{"marker":"ok"}}
}
→ {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"pong: ok"}]}}
```

## 六、已验证冒烟测试（Solon 侧过滤器方案）

| 测试 | 期望 | 实际 |
|---|---|---|
| pass 模式 tools/list | 200 + tools 列表 | ✅ 200 |
| pass 模式 tools/call ping | 200 + pong | ✅ 200 |
| deny 模式 tools/list（无 token） | 401 + unauthorized JSON | ✅ 401 |
| deny 模式 GET /sse（无 token） | 401 | ✅ 401 |

## 七、已知约束

- 端点路径固定在 `/mcp/*`、`/sse/*`，由 SolonServletFilter 的 URL 模式决定
- 校验器异常按 500 处理（避免把校验器故障当成鉴权失败）
- `/mcp/**/message`（Streamable 回发端点）不重复鉴权（SSE 连接建立时已验证）
- Solon 扫描被禁用（enableScanning(false)），所有端点必须通过 Spring Bean 手动构建
- 端点 channel 为 STREAMABLE_STATELESS（集群友好），不需要 session 管理
- TokenVerifier/TokenExtractor 是 Spring Bean，由 McpServerConfig 构造器注入，
  手动 new McpAuthFilter(verifier, extractor) 传入 Solon FilterChain
