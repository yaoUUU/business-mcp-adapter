package com.earchives.mcp.adapter.config;
import jakarta.annotation.PostConstruct;import org.springframework.core.env.Environment;import org.springframework.stereotype.Component;import org.springframework.util.StringUtils;
/**
 * 启动期安全配置闸门。
 *
 * <p>运行后再发现签名密钥缺失会让 OpenClaw 连接成功却无法安全调用工具，因此这里
 * 在 Spring 完成配置绑定后立即失败。非 local 环境同时拒绝示例 MCP Token，防止误把
 * 开发默认值部署到可访问网络。</p>
 */
@Component public class StartupSecurityValidator{
 private final McpAdapterProperties properties;private final Environment environment;
 public StartupSecurityValidator(McpAdapterProperties p,Environment e){properties=p;environment=e;}
 /** 校验每个启用客户端都有稳定 ID、入口 Token 和个人微信 HMAC 密钥。 */
 @PostConstruct public void validate(){if(properties.getAuth().getClients().isEmpty())throw new IllegalStateException("至少配置一个 MCP 客户端");for(McpAdapterProperties.Client c:properties.getAuth().getClients()){if(!c.isEnabled())continue;if(!StringUtils.hasText(c.getClientId()))throw new IllegalStateException("BUSINESS_MCP_CLIENT_ID 必须配置");if(!StringUtils.hasText(c.getToken()))throw new IllegalStateException("BUSINESS_MCP_TOKEN 必须配置");if(!StringUtils.hasText(c.getChannelSigningSecret())||c.getChannelSigningSecret().length()<32)throw new IllegalStateException("BUSINESS_MCP_CHANNEL_SECRET 必须至少 32 字符");if(!"local".equalsIgnoreCase(c.getEnvironment())&&"dev-token-change-me".equals(c.getToken()))throw new IllegalStateException("非 local 环境禁止使用默认 MCP Token");}}
}