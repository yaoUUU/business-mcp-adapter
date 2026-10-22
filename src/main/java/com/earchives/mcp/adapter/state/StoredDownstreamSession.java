package com.earchives.mcp.adapter.state;
/** Redis 会话模型；accessTokenCiphertext 必须是 AES-GCM 密文，绝不能进入日志或响应。 */
public record StoredDownstreamSession(int version,String principalHash,String accessTokenCiphertext,String clientId,
 Long userId,String username,String nickname,String mobile,String tenantId,long expiresAtMillis) {}