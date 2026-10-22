package com.earchives.mcp.adapter.tool;
import com.earchives.mcp.adapter.auth.RequestContext;import com.earchives.mcp.adapter.config.McpAdapterProperties;import com.earchives.mcp.adapter.exception.*;import com.earchives.mcp.adapter.mcp.*;import com.earchives.mcp.adapter.service.*;import java.util.*;import org.springframework.stereotype.Component;
/**
 * 查询当前已签名个人微信主体所绑定的原系统用户状态。
 * <p>公开 Schema 不再包含 userId；查询对象只能来自 Redis 会话，从接口层消除水平越权入口。</p>
 */
@Component public class CaPsnSilentAuthStatusTool implements McpTool{
 private final BusinessApiClient api;private final McpAdapterProperties properties;private final DownstreamAuthService auth;private final McpResponseFactory responses;
 public CaPsnSilentAuthStatusTool(BusinessApiClient a,McpAdapterProperties p,McpResponseFactory r,DownstreamAuthService d){api=a;properties=p;responses=r;auth=d;}
 public String name(){return "ca_psn_silent_auth_status";}
 public ToolDefinition definition(){Map<String,Object>p=Map.of("includeRecords",Map.of("type","boolean","description","是否返回历史记录，默认 false。"));return new ToolDefinition(name(),"查询当前微信用户授权状态","只查询当前个人微信绑定用户。以authorizationValid及authorizationStatusText判断当前静默授权，authed不是身份认证状态。latestAuth.status=2是授权成功，与accountStatus.status=2含义不同。当前有效时不能声称必须重新授权；null表示未知，不能认定未授权。",ToolSchemas.object(p,List.of()),SilentAuthStatusSummary.schema(),new ToolAnnotations(true,false,true,false),Map.of("riskLevel","LOW"));}
 public ToolCallResult execute(Map<String,Object>a,RequestContext c){DownstreamSession s=auth.current(c);if(s==null)throw new McpToolException("DOWNSTREAM_AUTH_REQUIRED","请先登录并绑定当前微信账号","请调用登录工具。",false);var p=properties.getTools().getPsnSilentAuth();Map<String,Object>out=new LinkedHashMap<>();out.put("loginUser",auth.publicSessionInfo(s));out.putAll(SilentAuthStatusSummary.summarize(read(p.getPsnAccountStatusMethod(),p.getPsnAccountStatusPath(),s),read(p.getPsnAuthedMethod(),p.getPsnAuthedPath(),s),read(p.getPsnAuthLatestMethod(),p.getPsnAuthLatestPath(),s)));if(ArgumentUtils.booleanValue(a,"includeRecords",false))out.put("records",SilentAuthStatusSummary.describeRecords(read(p.getPsnAuthListMethod(),p.getPsnAuthListPath(),s)));return responses.ok(name(),c.requestId(),SensitiveDataMasker.sanitize(out));}
 private Object read(String method,String path,DownstreamSession s){try{return call(method,path,s);}catch(RuntimeException e){return Map.of("available",false);}}
 private Object call(String method,String path,DownstreamSession s){return BusinessResponseUtils.unwrap(api.request(method,path,Map.of("userId",s.userId()),null,Map.of(),s));}
}
