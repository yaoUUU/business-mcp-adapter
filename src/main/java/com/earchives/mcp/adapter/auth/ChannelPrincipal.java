package com.earchives.mcp.adapter.auth;

/**
 * 通过 HMAC 验证后的个人微信主体。
 *
 * <p>原始微信用户标识只在当前请求内存中短暂存在；持久化、Redis Key 和审计均使用
 * {@code principalHash}。MCP 会话 ID 参与签名以阻止跨会话请求替换，但不参与主体摘要，
 * 因而同一微信账号可以在多个聊天会话间复用自己的原系统登录态。</p>
 */
public record ChannelPrincipal(String channel, String userId, String sessionId, String principalHash) {
}