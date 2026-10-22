package com.earchives.mcp.adapter.service;

/**
 * Adapter 动态登录原系统后得到的登录态。
 *
 * <p>accessToken 只在 adapter 内部用于调用原系统接口，不会返回给微信/OpenClaw。</p>
 */
public record DownstreamSession(
    String accessToken,
    String clientId,
    Long userId,
    String username,
    String nickname,
    String mobile,
    String tenantId,
    long expiresAtMillis
) {

    public boolean expired(long nowMillis) {
        return expiresAtMillis > 0 && nowMillis >= expiresAtMillis;
    }
}
