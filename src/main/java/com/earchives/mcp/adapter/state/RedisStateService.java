package com.earchives.mcp.adapter.state;

import com.earchives.mcp.adapter.config.AdapterSecurityProperties;
import com.earchives.mcp.adapter.exception.McpToolException;
import com.earchives.mcp.adapter.security.CryptoService;
import com.earchives.mcp.adapter.service.DownstreamSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * Adapter 的 Redis 状态边界。
 *
 * <p>所有 Key 都只使用主体摘要，不包含个人微信 ID。Redis 异常会向上抛出并使工具失败，
 * 绝不回退到进程级共享 Map。工作流锁采用 owner 随机值；释放时 Lua 脚本先比较 owner，
 * 防止旧请求误删锁租期结束后由新请求取得的锁。</p>
 */
@Service
public class RedisStateService {
 private static final String PREFIX="business:mcp:v1:";
 private static final DefaultRedisScript<Long> UNLOCK=new DefaultRedisScript<>("if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",Long.class);
 private final StringRedisTemplate redis; private final ObjectMapper mapper; private final CryptoService crypto; private final AdapterSecurityProperties properties;
 public RedisStateService(StringRedisTemplate redis,ObjectMapper mapper,CryptoService crypto,AdapterSecurityProperties properties){this.redis=redis;this.mapper=mapper;this.crypto=crypto;this.properties=properties;}

 /** 原子登记签名 nonce；返回 false 表示同一请求被重放。 */
 public boolean claimNonce(String clientId,String nonce){return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX+"nonce:"+clientId+":"+crypto.hash(nonce),"1",properties.getSecurity().getNonceTtl()));}

 /** 保存当前个人微信主体的原系统会话，Token 加密后才离开进程内存。 */
 public void saveSession(String principalHash,DownstreamSession session){
  StoredDownstreamSession stored=new StoredDownstreamSession(1,principalHash,crypto.encrypt(session.accessToken()),session.clientId(),session.userId(),session.username(),session.nickname(),session.mobile(),session.tenantId(),session.expiresAtMillis());
  write(PREFIX+"session:"+principalHash,stored,Duration.ofMillis(Math.max(1000,session.expiresAtMillis()-System.currentTimeMillis())));
 }
 /** 读取并验证主体绑定；任何主体不一致或过期状态都按未登录处理。 */
 public DownstreamSession loadSession(String principalHash){
  StoredDownstreamSession s=read(PREFIX+"session:"+principalHash,StoredDownstreamSession.class);
  if(s==null||!principalHash.equals(s.principalHash())||s.expiresAtMillis()<=System.currentTimeMillis()){deleteSession(principalHash);return null;}
  return new DownstreamSession(crypto.decrypt(s.accessTokenCiphertext()),s.clientId(),s.userId(),s.username(),s.nickname(),s.mobile(),s.tenantId(),s.expiresAtMillis());
 }
 public void deleteSession(String principalHash){redis.delete(PREFIX+"session:"+principalHash);}
 public void saveChallenge(SilentAuthChallenge c){write(PREFIX+"challenge:"+c.challengeId(),c,properties.getChallenge().getOperationRetention());redis.opsForValue().set(PREFIX+"active-challenge:"+c.principalHash(),c.challengeId(),properties.getChallenge().getTtl());}
 public SilentAuthChallenge challenge(String id){return read(PREFIX+"challenge:"+id,SilentAuthChallenge.class);}
 public SilentAuthChallenge active(String principalHash){String id=redis.opsForValue().get(PREFIX+"active-challenge:"+principalHash);return id==null?null:challenge(id);}
 public void invalidateActive(String principalHash){SilentAuthChallenge c=active(principalHash);if(c!=null)saveChallenge(c.withStatus(ChallengeStatus.EXPIRED,"用户已注销"));redis.delete(PREFIX+"active-challenge:"+principalHash);}

 /** 获取 Challenge 独占锁；调用方必须在 finally 中使用相同 owner 释放。 */
 public String tryLock(String challengeId){String owner=UUID.randomUUID().toString();Boolean ok=redis.opsForValue().setIfAbsent(PREFIX+"workflow-lock:"+challengeId,owner,properties.getChallenge().getWorkflowLockTtl());return Boolean.TRUE.equals(ok)?owner:null;}
 public void unlock(String challengeId,String owner){redis.execute(UNLOCK,Collections.singletonList(PREFIX+"workflow-lock:"+challengeId),owner);}
 private void write(String key,Object value,Duration ttl){try{redis.opsForValue().set(key,mapper.writeValueAsString(value),ttl);}catch(Exception e){throw unavailable(e);}}
 private <T>T read(String key,Class<T> type){try{String json=redis.opsForValue().get(key);return json==null?null:mapper.readValue(json,type);}catch(Exception e){throw unavailable(e);}}
 private McpToolException unavailable(Exception e){return new McpToolException("STATE_STORE_UNAVAILABLE","安全状态存储不可用","请检查 Adapter Redis 连接；为防止串号，服务不会降级到内存会话。",true);}
}