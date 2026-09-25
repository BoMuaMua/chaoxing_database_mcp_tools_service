# 连接池统一配置（config.pool 包）

## 一、设计问题

业务方不该关心连接池参数（最大连接数、超时时间、空闲回收等）。直接暴露
`HikariDataSource` 让业务读池参数，会破坏分层、耦合池实现。

**封装做法**：统一配一个连接池（HikariCP），对外只暴露"获取连接"的方法。
业务代码只注入 `ConnectionProvider`，调 `getConnection()`，不读池参数、不碰池实现。

## 二、连接链路

```
业务代码
  │  只注入 ConnectionProvider，调 getConnection()
  ▼
ConnectionProvider（config.pool，唯一门面）
  │  取连接失败 → DbException（1001/1002）
  ▼
GaarasonDataSource（@Primary，路由）→ HikariDataSource（Spring Boot 自动配置，读 spring.datasource.*）
```

> Gaarason starter 的 `GaarasonDataSource`（`@Primary`）通过 `ObjectProvider<DataSource>`
> 包装 Spring 自动配置的 `HikariDataSource` 做路由；`ConnectionProvider` 注入
> Gaarason 路由数据源，对外暴露唯一 `getConnection()`。

## 三、包结构

```
com.chaoxing.mcpserver.config.pool
├── PoolProperties.java            绑定 spring.datasource.hikari.*（池参数统一出口）
├── ConnectionPoolConfig.java       池配置装配（不手动定义 @Primary DataSource）
└── ConnectionProvider.java        业务方"获取连接"唯一门面（对外一个 getConnection()）
```

| 类 | 职责 |
|---|---|
| `ConnectionProvider` | 业务方"获取连接"**唯一入口**（对外一个 `getConnection()`）；不读池参数，不碰池实现；取连接失败统一转 `DbException`（1001/1002） |
| `PoolProperties` | 绑定 `spring.datasource.hikari.*`（池参数统一读取出口；将来加连接池监控/动态调参从这里走） |
| `ConnectionPoolConfig` | 池配置装配（**不手动定义 `@Primary DataSource`**，避免与 Gaarason 的 `gaarasonDataSource` 撞车；Gaarason 通过 `ObjectProvider<DataSource>` 包装 Spring 自动配置的池做路由） |

## 四、配置契约

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/mcp_tools?...   # 甲方连接信息到位后替换
    driver-class-name: com.mysql.cj.jdbc.Driver
    username: root
    password: root
    hikari:                               # 连接池参数统一收敛（业务代码不直接读）
      pool-name: McpDatabasePool
      maximum-pool-size: 10
      minimum-idle: 0
      connection-timeout: 5000            # 获取连接超时（毫秒，超出抛 1002）
      idle-timeout: 300000                # 空闲连接回收时间（毫秒）
      max-lifetime: 1800000               # 连接最大寿命（毫秒，防 MySQL 8h 空闲断连）
      connection-test-query: "SELECT 1"   # 连接有效性检测 SQL
      initialization-fail-timeout: -1     # 占位池不阻塞启动（真实接入后可改 5000）
```

运行时可用 JVM 参数覆盖：`-Dspring.datasource.hikari.maximum-pool-size=20`。

## 五、ConnectionProvider 实现要点

- 注入 Gaarason 的 `GaarasonDataSource`（`@Primary` 路由数据源）
- `getConnection()` 取连接失败时：
  - 直接 `SQLException` → 经 `DbExceptionMapper.mapConnectionFailure(e)` 转 `DbException`（1001/1002）
  - Gaarason 包装器抛的 `SQLRuntimeException`（cause 里包着底层 `SQLException`）→
    解 cause 链还原 `SQLException` 再交给 mapper，业务层不会拿到 Gaarason 原始异常

> **已知坑**：Gaarason 数据源包装器取连接失败时抛的是
> `gaarason.database.exception.SQLRuntimeException`（cause 里包着底层 `SQLException`），
> 不是 `SQLException`。`ConnectionProvider` 已处理：解 cause 链还原 `SQLException`
> 再交给 `DbExceptionMapper`，业务层不会拿到 Gaarason 原始异常。

## 六、为什么不用 Druid

项目引入 `druid-spring-boot-starter`（Gaarason starter 传递依赖）作为可选池实现，
但当前默认用 Spring Boot 自动配置的 **HikariCP**（`HikariDataSource`）。
HikariCP 性能/简洁度更适合本场景；将来要切 Druid 只需改 `PoolProperties` 绑定的前缀
+ `ConnectionPoolConfig` 装配逻辑，`ConnectionProvider` 对外接口不变（业务零改动）。
