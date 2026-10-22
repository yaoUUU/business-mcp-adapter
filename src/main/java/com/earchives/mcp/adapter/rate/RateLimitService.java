package com.earchives.mcp.adapter.rate;
import com.earchives.mcp.adapter.auth.AuthContext;import com.earchives.mcp.adapter.config.McpAdapterProperties;import com.earchives.mcp.adapter.exception.McpToolException;import org.springframework.data.redis.core.StringRedisTemplate;import org.springframework.stereotype.Service;import java.time.Duration;
/**
 * Redis 固定窗口限流器。
 *
 * <p>计数状态由所有 Adapter 实例共享，Redis 不可用时直接失败，避免实例切换绕过限流。
 * INCR 首次返回 1 时设置窗口 TTL；极端崩溃可能留下无 TTL Key，因此同时补查 TTL。</p>
 */
@Service public class RateLimitService{
 private static final String PREFIX="business:mcp:v1:rate:";private final McpAdapterProperties properties;private final StringRedisTemplate redis;
 public RateLimitService(McpAdapterProperties p,StringRedisTemplate r){properties=p;redis=r;}
 /** 按 MCP 客户端和工具维度检查请求额度。 */
 public void check(AuthContext context,String tool){try{String key=PREFIX+context.tokenHash()+":"+(tool==null?"mcp":tool);Long count=redis.opsForValue().increment(key);Duration window=properties.getRateLimit().getWindow();if(count!=null&&(count==1||Boolean.FALSE.equals(redis.hasKey(key))))redis.expire(key,window);if(count!=null&&count>properties.getRateLimit().getMaxRequests())throw new McpToolException("RATE_LIMITED","调用过于频繁","请在当前限流窗口结束后重试。",true);}catch(McpToolException e){throw e;}catch(RuntimeException e){throw new McpToolException("STATE_STORE_UNAVAILABLE","Redis 限流状态不可用","请检查 Redis；服务不会降级到进程内限流。",true);}}
}