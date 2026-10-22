package com.earchives.mcp.adapter.auth;

import java.util.Set;

/**
 * 已通过 MCP Bearer Token 鉴权的 OpenClaw 客户端上下文。
 *
 * <p>{@code channelSigningSecret} 是敏感数据，只允许签名验证器读取，任何日志、响应和
 * Redis 对象都不得包含它。{@code tokenHash} 仅用于限流和审计关联，不能代替个人微信身份。</p>
 */
public record AuthContext(String mcpClientId, String environment, String tokenHash,
                          String channelSigningSecret, Set<String> allowedTools) {
    /** 判断当前 OpenClaw 客户端是否获得指定工具能力。 */
    public boolean canUseTool(String toolName) {
        return allowedTools != null && (allowedTools.contains("*") || allowedTools.contains(toolName));
    }
}