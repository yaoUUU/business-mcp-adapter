package com.earchives.mcp.adapter.tool;
import com.earchives.mcp.adapter.auth.RequestContext;import com.earchives.mcp.adapter.mcp.*;import com.earchives.mcp.adapter.service.*;import java.util.*;import org.springframework.stereotype.Component;
/** 发起与当前个人微信主体强绑定的三要素短信 Challenge；不向 Agent 暴露下游 flowId。 */
@Component public class CaPsnSilentAuthStartTool implements McpTool{
 private final SilentAuthWorkflowService workflow;private final McpResponseFactory responses;public CaPsnSilentAuthStartTool(SilentAuthWorkflowService w,McpResponseFactory r){workflow=w;responses=r;}
 public String name(){return "ca_psn_silent_auth_start";}
 public ToolDefinition definition(){Map<String,Object>p=new LinkedHashMap<>();p.put("name",ToolSchemas.string("真实姓名。",1,64,null));p.put("mobileNo",ToolSchemas.string("个人微信用户本人的开户注册手机号。",11,11,"^1[3-9]\\d{9}$"));p.put("idCardNo",ToolSchemas.string("身份证号，仅用于三要素验证。",15,18,"^[0-9]{15}|[0-9]{17}[0-9Xx]$"));return new ToolDefinition(name(),"发送个人静默授权验证码","发送短信并返回一次性 challengeId。",ToolSchemas.object(p,List.of("name","mobileNo","idCardNo")),ToolSchemas.result(),new ToolAnnotations(false,false,false,false),Map.of("riskLevel","MEDIUM"));}
 public ToolCallResult execute(Map<String,Object>a,RequestContext c){String n=ArgumentUtils.requiredString(a,"name","张三"),m=ArgumentUtils.requiredString(a,"mobileNo","13700000000"),i=ArgumentUtils.requiredString(a,"idCardNo","330000000000000000");return responses.ok(name(),c.requestId(),workflow.start(n,m,i,c));}
}