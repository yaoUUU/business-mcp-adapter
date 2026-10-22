package com.earchives.mcp.adapter.web;

import com.earchives.mcp.adapter.audit.AuditService;import com.earchives.mcp.adapter.auth.*;import com.earchives.mcp.adapter.config.McpAdapterProperties;import com.earchives.mcp.adapter.exception.*;import com.earchives.mcp.adapter.mcp.*;import com.earchives.mcp.adapter.rate.RateLimitService;import com.earchives.mcp.adapter.service.*;import com.earchives.mcp.adapter.tool.*;import com.fasterxml.jackson.core.type.TypeReference;import com.fasterxml.jackson.databind.ObjectMapper;import jakarta.servlet.http.HttpServletRequest;import org.springframework.http.*;import org.springframework.util.StringUtils;import org.springframework.web.bind.annotation.*;import java.util.*;

/**
 * MCP JSON-RPC HTTP 入口以及个人微信信任边界。
 *
 * <p>Controller 保留原始 JSON 字节，使 HMAC 覆盖 OpenClaw 实际发送的正文；只有
 * tools/call 需要个人微信身份，initialize/tools/list 仍可由 MCP 客户端建立全局连接。
 * Notification 没有 JSON-RPC 响应体，符合生命周期语义。</p>
 */
@RestController @RequestMapping("/mcp") public class McpController{
 private final McpAdapterProperties properties;private final AuthService auth;private final WechatSignatureService signatures;private final RateLimitService limits;private final AuditService audit;private final ToolRegistry registry;private final McpResponseFactory responses;private final ObjectMapper mapper;
 public McpController(McpAdapterProperties p,AuthService a,WechatSignatureService s,RateLimitService l,AuditService au,ToolRegistry r,McpResponseFactory f,ObjectMapper m){properties=p;auth=a;signatures=s;limits=l;audit=au;registry=r;responses=f;mapper=m;}

 /** 处理单条 JSON-RPC 请求；请求解析前保留原始字节供签名正文摘要使用。 */
 @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<?> handle(@RequestBody byte[] raw,@RequestHeader(value="Authorization",required=false)String authorization,HttpServletRequest servlet){
  long started=System.currentTimeMillis();Map<String,Object>request;Object id=null;String method=null,toolName=null,errorCode=null,requestId=resolveRequestId(servlet);AuthContext client=null;ChannelPrincipal principal=null;boolean ok=false;
  try{
   request=mapper.readValue(raw,new TypeReference<>(){});id=request.get("id");method=text(request.get("method"));
   if(!"2.0".equals(request.get("jsonrpc")))return ResponseEntity.ok(JsonRpcResponse.error(id,-32600,"jsonrpc 必须为 2.0",null));
   if(!StringUtils.hasText(method))return ResponseEntity.ok(JsonRpcResponse.error(id,-32600,"method 不能为空",null));
   client=auth.authenticate(authorization);limits.check(client,null);
   // initialized 是无 id Notification；确认收到即可，不生成错误响应或 JSON body。
   if("notifications/initialized".equals(method))return ResponseEntity.noContent().build();
   JsonRpcResponse response;
   if("initialize".equals(method))response=JsonRpcResponse.result(id,initialize());
   else if("tools/list".equals(method))response=JsonRpcResponse.result(id,Map.of("tools",registry.list(client)));
   else if("tools/call".equals(method)){
    principal=signatures.verify(servlet,raw,client);Payload payload=parse(request);toolName=payload.name();limits.check(client,toolName);McpTool tool=registry.get(toolName,client);
    ToolCallResult result=tool.execute(payload.arguments(),new RequestContext(requestId,client,principal));ok=!result.isError();if(result.isError())errorCode="TOOL_ERROR";response=JsonRpcResponse.result(id,result);
   }else response=JsonRpcResponse.error(id,-32601,"不支持的 MCP 方法："+method,null);
   if(!"tools/call".equals(method))ok=response.error()==null;
   // 对任意无 id Notification 均不返回 JSON-RPC body。
   if(id==null)return ResponseEntity.noContent().build();return ResponseEntity.ok(response);
  }catch(McpAuthException e){errorCode=e.getErrorCode();return ResponseEntity.ok(JsonRpcResponse.error(id,-32001,e.getMessage(),Map.of("errorCode",e.getErrorCode(),"suggestion",e.getSuggestion(),"requestId",requestId)));}
  catch(McpToolException e){errorCode=e.getErrorCode();ToolCallResult result=responses.fail(toolName==null?"mcp":toolName,requestId,e.getErrorCode(),e.getMessage(),e.getSuggestion(),e.isRetryable());return ResponseEntity.ok(JsonRpcResponse.result(id,result));}
  catch(Exception e){errorCode="INVALID_REQUEST";return ResponseEntity.ok(JsonRpcResponse.error(id,-32602,"请求 JSON 或参数不合法",Map.of("requestId",requestId)));}
  finally{audit.record(client,requestId,method,toolName,ok,errorCode,System.currentTimeMillis()-started);}
 }
 private Map<String,Object>initialize(){Map<String,Object>result=new LinkedHashMap<>();result.put("protocolVersion",properties.getProtocolVersion());result.put("serverInfo",Map.of("name",properties.getServerName(),"version",properties.getServerVersion()));result.put("capabilities",Map.of("tools",Map.of("listChanged",false)));result.put("instructions",properties.getInstructions());return result;}
 @SuppressWarnings("unchecked") private Payload parse(Map<String,Object>request){Object raw=request.get("params");if(!(raw instanceof Map<?,?>m))throw new IllegalArgumentException();String name=text(m.get("name"));if(!StringUtils.hasText(name))throw new McpToolException("INVALID_ARGUMENT","工具名称不能为空","请先调用 tools/list。",false);Object a=m.get("arguments");if(a==null)return new Payload(name,Map.of());if(!(a instanceof Map<?,?>))throw new IllegalArgumentException();return new Payload(name,(Map<String,Object>)a);}
 private String resolveRequestId(HttpServletRequest r){String h=r.getHeader("X-Request-Id");return StringUtils.hasText(h)&&h.matches("[A-Za-z0-9._:-]{1,128}")?h:RequestIds.next();}
 private String text(Object v){return v==null?null:String.valueOf(v);}private record Payload(String name,Map<String,Object>arguments){}
}