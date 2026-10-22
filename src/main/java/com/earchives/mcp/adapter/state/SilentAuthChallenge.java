package com.earchives.mcp.adapter.state;

/**
 * Redis 中的个人静默授权工作流快照。
 *
 * <p>微信 ID、手机号、身份证号均不保存明文；flowId 使用 AES-GCM 密文保存。
 * {@code version} 是序列化版本，为未来兼容迁移保留，不代表并发锁版本。</p>
 */
public record SilentAuthChallenge(int version, String challengeId, String principalHash, Long downstreamUserId,
 String tenantId, String encryptedFlowId, String encryptedMobile, String mobileHash, String maskedMobile, String identityHash,
 ChallengeStatus status, int failedAttempts, long createdAt, long expiresAt, String lastError) {
 public SilentAuthChallenge withStatus(ChallengeStatus next,String error){return new SilentAuthChallenge(version,challengeId,principalHash,downstreamUserId,tenantId,encryptedFlowId,encryptedMobile,mobileHash,maskedMobile,identityHash,next,failedAttempts,createdAt,expiresAt,error);}
 public SilentAuthChallenge withFlow(String flow,ChallengeStatus next){return new SilentAuthChallenge(version,challengeId,principalHash,downstreamUserId,tenantId,flow,encryptedMobile,mobileHash,maskedMobile,identityHash,next,failedAttempts,createdAt,expiresAt,null);}
 public SilentAuthChallenge failedAttempt(ChallengeStatus next,String error){return new SilentAuthChallenge(version,challengeId,principalHash,downstreamUserId,tenantId,encryptedFlowId,encryptedMobile,mobileHash,maskedMobile,identityHash,next,failedAttempts+1,createdAt,expiresAt,error);}
}