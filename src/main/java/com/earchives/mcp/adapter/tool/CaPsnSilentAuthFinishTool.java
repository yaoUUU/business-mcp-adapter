package com.earchives.mcp.adapter.tool;
import com.earchives.mcp.adapter.auth.RequestContext;import com.earchives.mcp.adapter.mcp.*;import com.earchives.mcp.adapter.service.*;import java.util.*;import org.springframework.stereotype.Component;
/**
 * 使用服务端 Challenge 完成静默授权。
 * <p>工具不接受 userId、手机号或 flowId，防止模型把短信验证结果替换到其他业务对象。</p>
 */
@Component public class CaPsnSilentAuthFinishTool implements McpTool{
 private final SilentAuthWorkflowService workflow;private final McpResponseFactory responses;public CaPsnSilentAuthFinishTool(SilentAuthWorkflowService w,McpResponseFactory r){workflow=w;responses=r;}
 public String name(){return "ca_psn_silent_auth_finish";}
 public ToolDefinition definition(){Map<String,Object>p=new LinkedHashMap<>();p.put("challengeId",ToolSchemas.string("start 返回的一次性 Challenge ID。",36,36,"^[0-9a-fA-F-]{36}$"));p.put("authCode",ToolSchemas.string("个人微信用户收到的短信验证码。",4,8,"^\\d{4,8}$"));p.put("riskAccepted",Map.of("type","boolean","description","用户明确确认授权后必须为 true。"));return new ToolDefinition(name(),"完成个人静默签章授权","验证短信并从持久化步骤继续完成授权。",ToolSchemas.object(p,List.of("challengeId","authCode","riskAccepted")),ToolSchemas.result(),new ToolAnnotations(false,false,false,false),Map.of("riskLevel","HIGH"));}
 public ToolCallResult execute(Map<String,Object>a,RequestContext c){ArgumentUtils.requiredTrue(a,"riskAccepted","完成静默授权必须取得用户明确确认");String id=ArgumentUtils.requiredString(a,"challengeId","UUID"),code=ArgumentUtils.requiredString(a,"authCode","123456");return responses.ok(name(),c.requestId(),workflow.finish(id,code,c));}
}