package com.earchives.mcp.adapter;

import com.earchives.mcp.adapter.config.McpAdapterProperties;
import com.earchives.mcp.adapter.config.AdapterSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;


/**
 * 独立 MCP Adapter 的 Spring Boot 启动入口。
 *
 * <p>这个模块不依赖原系统的启动类，也不加入原系统父 pom 的 modules。
 * 启动后只暴露 /mcp 和 actuator 健康检查接口，用于让 OpenClaw 通过
 * streamable-http 方式调用个人 CA 静默签章授权工具。</p>
 */
@SpringBootApplication
@EnableConfigurationProperties({McpAdapterProperties.class, AdapterSecurityProperties.class})
public class BusinessMcpAdapterApplication {

    /**
     * 运行独立适配器进程。
     *
     * <p>运行时依赖 BUSINESS_API_BASE_URL、BUSINESS_API_TOKEN、
     * BUSINESS_MCP_TOKEN 等环境变量；这些配置会被绑定到
     * {@link McpAdapterProperties}。</p>
     */
    public static void main(String[] args) {
        SpringApplication.run(BusinessMcpAdapterApplication.class, args);
    }
}
