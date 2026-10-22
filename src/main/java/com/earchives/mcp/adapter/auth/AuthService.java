package com.earchives.mcp.adapter.auth;

import com.earchives.mcp.adapter.config.McpAdapterProperties;
import com.earchives.mcp.adapter.exception.McpAuthException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;

/**
 * OpenClaw 到 MCP Adapter 的访问鉴权服务。
 *
 * <p>注意这里校验的是 MCP 客户端请求 /mcp 时携带的 BUSINESS_MCP_TOKEN，
 * 不是 adapter 调用原业务系统时使用的 BUSINESS_API_TOKEN。两者职责不同：
 * 前者保护 adapter 入口，后者代表原系统登录态。</p>
 */
@Service
public class AuthService {

    private static final String BEARER_PREFIX = "Bearer ";
    private final McpAdapterProperties properties;

    public AuthService(McpAdapterProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验 Authorization: Bearer xxx，并生成请求上下文。
     *
     * <p>鉴权成功后返回允许使用的工具集合，后续 tools/list 和 tools/call 都会
     * 根据这个集合过滤，避免某个 OpenClaw 环境调用未授权工具。</p>
     */
    public AuthContext authenticate(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            throw new McpAuthException("UNAUTHORIZED", "缺少 Bearer Token", "请在 Authorization 请求头中传入 Bearer Token。");
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (!StringUtils.hasText(token)) {
            throw new McpAuthException("UNAUTHORIZED", "Bearer Token 不能为空", "请检查 OpenClaw 的 MCP Adapter 凭据配置。");
        }

        for (McpAdapterProperties.Client client : properties.getAuth().getClients()) {
            if (client.isEnabled() && secureEquals(token, client.getToken())) {
                return new AuthContext(client.getClientId(), client.getEnvironment(), sha256(token), client.getChannelSigningSecret(), new LinkedHashSet<>(client.getTools()));
            }
        }

        throw new McpAuthException("UNAUTHORIZED", "Bearer Token 无效", "请检查 token 是否过期或是否属于当前 OpenClaw 环境。");
    }

    /**
     * 使用 MessageDigest.isEqual 做常量时间比较，降低 token 比较中的时序泄露风险。
     */
    private boolean secureEquals(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return false;
        }
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(leftBytes, rightBytes);
    }

    /**
     * 生成短 hash 用于审计日志。
     *
     * <p>这里只截取前 8 个字节的十六进制字符串，目的是定位同一个客户端，
     * 而不是反推出 token 明文。</p>
     */
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < Math.min(bytes.length, 8); i++) {
                builder.append(String.format("%02x", bytes[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
