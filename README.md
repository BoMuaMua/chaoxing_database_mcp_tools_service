# MCP Database Tools Server（MCP 基座）

MCP（Model Context Protocol）数据库工具基座服务：MCP 客户端（AI 智能体 / LLM 应用）通过 MCP 端点调用本服务，服务再查询数据库。

- 技术栈：Java 17 · Spring Boot 3.3.13 · solon-ai-mcp 3.9.5（MCP 端点由 Solon 容器管理，Spring Boot 提供 Web 容器）
- 数据库：`database-spring-boot-starter`（Gaarason）
- 构建：Maven

```bash
# 编译
mvn -q clean compile
# 启动（默认 pass 模式）
mvn spring-boot:run
# 启动（deny 模式，联调灰度封禁）
mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Dmcp.auth.mode=deny"
```

---

# Auth 鉴权框架（调用方鉴权）

## 一、鉴权保护的是什么

本框架做 **调用方鉴权（caller-side auth）**：校验"谁有权调用这个 MCP 服务"，
保护 MCP 端点不被任意第三方直接访问。

**职责边界**：MCP 协议本身无法识别终端用户是谁（用户身份只能由智能体通过
工具参数 / 请求头透传，如 `@Header("user")` 或工具参数里的 `userId`）。
用户级的授权与审计属于**工具层职责**，不在 Auth 包范围内。

## 二、请求链（已生效）

```
HTTP 请求
  │
  ▼
Spring Tomcat（8080）
  │
  ├─ SolonServletFilter（FilterRegistrationBean 桥接 /mcp/*、/sse/* 到 Solon 容器）
  │
  ▼
Solon 容器 FilterChain
  │
  ├─ McpAuthFilter（Solon Filter，token 校验，先于 MCP 协议帧解析）
  │     不通过 → ctx.status(401/403) + output + setHandled(true) → 链路终止，
  │             MCP 协议层不可见未授权请求
  │
  ▼
MCP 端点（@McpServerEndpoint + @ToolMapping 工具方法）
```

MCP 客户端调用示例（Streamable Stateless）：

```
POST http://host:8080/mcp/database-tools
Content-Type: application/json
Accept: application/json, text/event-stream
Authorization: Bearer <token>

{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
```

## 三、包结构与职责

```
com.chaoxing.mcpserver.auth
├── TokenVerifier.java            校验核心接口：verify(String token, Context ctx)
├── TokenExtractor.java           提取接口：extract(Context ctx)
├── TokenVerificationResult.java  结果值对象（401/403 语义 + claims 扩展点）
├── filter/
│   └── McpAuthFilter.java        Solon Filter，拦截 /mcp/*、/sse/*（挂载层，稳定不动）
├── impl/
│   ├── NoopTokenVerifier.java    空置默认实现（pass / deny / custom 三模式）
│   └── DefaultTokenExtractor.java  Bearer 头 → X-Auth-Token 头 → ?token= 查询串
└── config/
    ├── AuthConfiguration.java    Bean 装配（TokenExtractor + TokenVerifier + AuthProperties）
    ├── AuthProperties.java       绑定 mcp.auth.* 配置（@EnableConfigurationProperties）
    └── AuthMode.java            pass | deny | custom 枚举

com.chaoxing.mcpserver.mcp
├── McpServerConfig.java          Solon.start() + app.router().filter(McpAuthFilter)
│                                 + 手动构建端点 provider + 注册 SolonServletFilter
└── McpDatabaseToolsEndpoint.java 首个端点（占位 ping 工具），后续数据库工具在此扩展
```

## 四、配置契约

```yaml
# application.yml
mcp:
  auth:
    mode: pass                # pass（默认，空置放行）| deny（空置拒绝）| custom（委托自定义实现）
    custom-verifier-bean: ""  # mode=custom 时，指定目标 TokenVerifier Bean 名
```

运行时可用 JVM 参数覆盖：`-Dmcp.auth.mode=deny`。

### 行为矩阵

| mode | token 携带 | 行为 | 日志 |
|---|---|---|---|
| pass | 有 | 放行 | DEBUG |
| pass | 无 | 放行 | WARN（提醒非安全模式） |
| deny | 任意 | 拒绝 401 | WARN |
| custom | 任意 | 委托 custom-verifier-bean 实现 | 取决于实现 |
| 任意 | 校验器抛异常 | 500（不当鉴权失败处理） | ERROR |

### 日志约定

- 校验失败 → WARN，**绝不记录 token 明文**
- 校验通过 → DEBUG
- `/mcp/**/message`（Streamable 回发端点）不重复鉴权（SSE 连接建立时已验证）

## 五、未来完善：写入真正的鉴权逻辑（甲方逻辑落地步骤）

> 原则：挂载层（`McpAuthFilter` / `McpServerConfig`）稳定不动，
> 只需新增实现类 + 切配置，零侵入。

**Step 1 — 新增 TokenVerifier 实现类**

放在 `com.chaoxing.mcpserver` 包下（Spring 组件扫描范围），加 `@Component` 注册为 Bean：

```java
package com.chaoxing.mcpserver.auth.impl;

import com.chaoxing.mcpserver.auth.TokenVerificationResult;
import com.chaoxing.mcpserver.auth.TokenVerifier;
import org.noear.solon.core.handle.Context;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 甲方提供的加密/签名校验逻辑（示例骨架）。 */
@Component
public class CryptoTokenVerifier implements TokenVerifier {

    @Override
    public TokenVerificationResult verify(String token, Context ctx) {
        if (token == null || token.isBlank()) {
            return TokenVerificationResult.unauthorized("token missing");
        }
        // TODO: 调用甲方加密/签名校验（如验签、查调用方白名单等）
        boolean ok = cryptoCheck(token);
        if (!ok) {
            return TokenVerificationResult.unauthorized("token signature invalid");
        }
        // 可选：携带调用方声明（claims），由过滤器写入 ctx.attrSet("mcp.auth.claims", ...)
        return TokenVerificationResult.pass(Map.of("clientName", "some-caller"));
    }

    private boolean cryptoCheck(String token) {
        return true; // 甲方逻辑
    }
}
```

要点：
- `TokenVerificationResult` 工厂方法：`pass()` / `pass(claims)` / `unauthorized(reason)` / `forbidden(reason)`
- `Context` 是扩展点：实现方可读取 `ctx.header(...)` / `ctx.param(...)` / `ctx.pathNew()` 等元数据
- reason 会出现在 WARN 日志与 401/403 响应体中，**禁止包含 token 明文**

**Step 2 — 切换配置**

```yaml
mcp:
  auth:
    mode: custom
    custom-verifier-bean: cryptoTokenVerifier   # 新实现类的 Bean 名（默认取类名首字母小写）
```

**Step 3 — 验证**

启动后：不带 token → 401；带合法 token → 200 tools 列表；带非法 token → 401/403。

`NoopTokenVerifier` 的 CUSTOM 分支通过 `beanFactory.isTypeMatch(beanName, TokenVerifier.class)`
精确解析目标 Bean 并委托校验；Bean 不存在或类型不符时拒绝请求并打 WARN/ERROR。

## 六、已验证冒烟测试

| 测试 | 期望 | 实际 |
|---|---|---|
| pass 模式 `tools/list` | 200 + tools 列表 | ✅ |
| pass 模式 `tools/call ping` | 200 + pong | ✅ |
| deny 模式 `tools/list`（无 token） | 401 unauthorized JSON | ✅ |
| deny 模式 `GET /sse` | 401 | ✅ |

## 七、已知约束

- 端点路径固定在 `/mcp/*`、`sse/*`（SolonServletFilter URL 模式）
- 端点 channel = STREAMABLE_STATELESS（集群友好，无 session）
- Solon 扫描禁用（`enableScanning(false)`），MCP 端点必须通过 Spring Bean 手动构建
- `McpAuthFilter` 的 `TokenVerifier`/`TokenExtractor` 是 Spring Bean，由 `McpServerConfig`
  构造器注入后手动 `new McpAuthFilter(...)` 传入 Solon FilterChain
- DataSource 当前为占位配置（`127.0.0.1:3306/mcp_tools`），甲方连接信息到位后替换
