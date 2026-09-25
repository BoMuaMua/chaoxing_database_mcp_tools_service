package com.chaoxing.mcpserver.db;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * {@link DataSource} 的统一访问入口（薄封装）。
 * <p>
 * 直接注入 Spring 容器的 {@code DataSource} Bean（由 Gaarason starter 注册为
 * 路由数据源 {@code GaarasonRoutingDataSourceWrapper}）。抽成组件便于将来
 * 按端点/工具做数据源切换、或加只读从库路由，而不必改动工具方法。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class DataSourceProvider {

    private final DataSource dataSource;

    public DataSource getDataSource() {
        return dataSource;
    }
}
