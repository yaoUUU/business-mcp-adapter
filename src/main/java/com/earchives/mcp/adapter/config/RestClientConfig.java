package com.earchives.mcp.adapter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 原业务系统 HTTP 客户端配置。
 *
 * <p>所有 /ca/local/... 调用最终都会经过这里创建的 {@link RestClient}。
 * baseUrl 和超时时间来自 application.yml/环境变量，这样 adapter 可以独立
 * 指向本机、局域网或线上部署的原系统，而不用修改代码。</p>
 */
@Configuration
public class RestClientConfig {

    /**
     * 创建专门访问原业务系统的 RestClient。
     *
     * <p>这里只配置连接参数，不在这里写 Authorization/clientid。
     * 鉴权头由 {@code BusinessApiClient} 在每次请求时根据最新 token 动态补齐。</p>
     */
    @Bean
    public RestClient businessRestClient(RestClient.Builder builder, McpAdapterProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(toMillis(properties.getDownstream().getConnectTimeout()));
        factory.setReadTimeout(toMillis(properties.getDownstream().getReadTimeout()));

        return builder
            .baseUrl(properties.getDownstream().getBaseUrl())
            .requestFactory(factory)
            .build();
    }

    /**
     * Spring 的 SimpleClientHttpRequestFactory 使用 int 毫秒值。
     *
     * <p>配置层使用 {@link java.time.Duration} 更直观；这里统一转换并处理极端
     * 超大值，避免强转溢出。</p>
     */
    private int toMillis(java.time.Duration duration) {
        long millis = duration == null ? 0L : duration.toMillis();
        return millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
    }
}
