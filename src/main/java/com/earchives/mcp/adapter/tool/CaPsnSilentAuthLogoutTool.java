package com.earchives.mcp.adapter.tool;
import com.earchives.mcp.adapter.auth.RequestContext;import com.earchives.mcp.adapter.mcp.*;import com.earchives.mcp.adapter.service.McpResponseFactory;import com.earchives.mcp.adapter.state.RedisStateService;import java.util.*;import org.springframework.stereotype.Component;
/**
 * 注销当前个人微信主体。
 * <p>只删除当前主体的加密下游会话，并使其活动 Challenge 失效；不会影响共享 MCP Token 下的其他微信账号。</p>
 */
@Component public class CaPsnSilentAuthLogoutTool implements McpTool{
 private final RedisStateService state;private final McpResponseFactory responses;public CaPsnSilentAuthLogoutTool(RedisStateService s,McpResponseFactory r){state=s;responses=r;}
 public String name(){return "ca_psn_silent_auth_logout";}
 public ToolDefinition definition(){return new ToolDefinition(name(),"注销当前个人微信绑定","清除当前个人微信账号的原系统会话和未完成 Challenge。",ToolSchemas.object(Map.of(),List.of()),ToolSchemas.result(),new ToolAnnotations(false,false,false,false),Map.of("riskLevel","LOW"));}
 public ToolCallResult execute(Map<String,Object>a,RequestContext c){String p=c.principal().principalHash();state.deleteSession(p);state.invalidateActive(p);return responses.ok(name(),c.requestId(),Map.of("loggedOut",true));}
}