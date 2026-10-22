package com.earchives.mcp.adapter.tool;
import com.earchives.mcp.adapter.auth.*;import com.earchives.mcp.adapter.mcp.*;import com.earchives.mcp.adapter.service.*;import java.util.*;import org.springframework.stereotype.Component;
/**
 * 把当前已签名个人微信主体绑定到一个原系统账号。
 *
 * <p>密码仅用于本次下游登录，不进入 Redis；Redis 只保存 AES-GCM 加密后的 access token。
 * 相同微信用户重新登录只替换自己的会话，不会影响共享 MCP Token 下的其他微信用户。</p>
 */
@Component public class CaPsnSilentAuthLoginTool implements McpTool{
 private final DownstreamAuthService auth;private final McpResponseFactory responses;public CaPsnSilentAuthLoginTool(DownstreamAuthService a,McpResponseFactory r){auth=a;responses=r;}
 public String name(){return "ca_psn_silent_auth_login";}
 public ToolDefinition definition(){Map<String,Object>p=new LinkedHashMap<>();addLoginFields(p);return new ToolDefinition(name(),"登录并绑定当前个人微信","使用原系统账号密码登录，并仅绑定到当前经过签名验证的个人微信账号。",ToolSchemas.object(p,List.of("username","password")),ToolSchemas.result(),new ToolAnnotations(false,false,false,false),Map.of("riskLevel","MEDIUM"));}
 public ToolCallResult execute(Map<String,Object>a,RequestContext c){DownstreamSession s=auth.login(a,c);return responses.ok(name(),c.requestId(),auth.publicSessionInfo(s));}
 static void addLoginFields(Map<String,Object>p){p.put("username",ToolSchemas.string("原系统登录账号。",1,128,null));p.put("password",ToolSchemas.string("原系统登录密码，仅用于本次登录。",1,256,null));p.put("tenantId",ToolSchemas.string("租户 ID，默认 000000。",1,32,null));p.put("clientId",ToolSchemas.string("原系统登录客户端 ID。",1,128,null));p.put("grantType",ToolSchemas.string("授权类型，默认 password。",1,32,null));p.put("code",ToolSchemas.string("登录验证码。",1,16,null));p.put("uuid",ToolSchemas.string("登录验证码 uuid。",1,128,null));}
}