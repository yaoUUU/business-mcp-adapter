package com.earchives.mcp.adapter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/** Adapter 自身的签名、加密、会话和一次性挑战配置。 */
@ConfigurationProperties(prefix = "business.mcp")
public class AdapterSecurityProperties {
    private Security security = new Security();
    private Session session = new Session();
    private Challenge challenge = new Challenge();
    public Security getSecurity(){return security;} public void setSecurity(Security v){security=v;}
    public Session getSession(){return session;} public void setSession(Session v){session=v;}
    public Challenge getChallenge(){return challenge;} public void setChallenge(Challenge v){challenge=v;}
    /** 请求签名与 Redis 敏感状态加密配置。 */
    public static class Security {
        private Duration signatureSkew=Duration.ofMinutes(5), nonceTtl=Duration.ofMinutes(10);
        private String dataEncryptionKey;
        public Duration getSignatureSkew(){return signatureSkew;} public void setSignatureSkew(Duration v){signatureSkew=v;}
        public Duration getNonceTtl(){return nonceTtl;} public void setNonceTtl(Duration v){nonceTtl=v;}
        public String getDataEncryptionKey(){return dataEncryptionKey;} public void setDataEncryptionKey(String v){dataEncryptionKey=v;}
    }
    /** 原系统动态登录态的最长缓存时间。 */
    public static class Session { private Duration ttl=Duration.ofMinutes(25); public Duration getTtl(){return ttl;} public void setTtl(Duration v){ttl=v;} }
    /** 短信挑战、失败次数和工作流锁的生命周期配置。 */
    public static class Challenge {
        private Duration ttl=Duration.ofMinutes(5),startCooldown=Duration.ofSeconds(60),operationRetention=Duration.ofHours(24),workflowLockTtl=Duration.ofMinutes(2);
        private int maxCodeAttempts=5;
        public Duration getTtl(){return ttl;} public void setTtl(Duration v){ttl=v;}
        public Duration getStartCooldown(){return startCooldown;} public void setStartCooldown(Duration v){startCooldown=v;}
        public Duration getOperationRetention(){return operationRetention;} public void setOperationRetention(Duration v){operationRetention=v;}
        public Duration getWorkflowLockTtl(){return workflowLockTtl;} public void setWorkflowLockTtl(Duration v){workflowLockTtl=v;}
        public int getMaxCodeAttempts(){return maxCodeAttempts;} public void setMaxCodeAttempts(int v){maxCodeAttempts=v;}
    }
}