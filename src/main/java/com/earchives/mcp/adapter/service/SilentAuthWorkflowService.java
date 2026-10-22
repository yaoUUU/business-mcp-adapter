package com.earchives.mcp.adapter.service;

import com.earchives.mcp.adapter.auth.RequestContext;
import com.earchives.mcp.adapter.config.AdapterSecurityProperties;
import com.earchives.mcp.adapter.config.McpAdapterProperties;
import com.earchives.mcp.adapter.exception.McpToolException;
import com.earchives.mcp.adapter.security.CryptoService;
import com.earchives.mcp.adapter.state.*;
import com.earchives.mcp.adapter.tool.BusinessResponseUtils;
import com.earchives.mcp.adapter.tool.SensitiveDataMasker;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * 个人静默签章授权的 Redis 可恢复状态机。
 *
 * <p>该服务把模型提供的数据限制在“发起三要素”和“输入短信验证码”两个入口，目标
 * userId、手机号和 flowId 均从已绑定会话及服务端 Challenge 取得。每个远程步骤成功后
 * 立即落盘，重试会跳过已确认步骤。旧创建接口没有幂等键，发送前先保存
 * APPLICATION_UNKNOWN；结果不确定时只查询，不重放创建或批准请求。</p>
 */
@Service
public class SilentAuthWorkflowService {
 private final BusinessApiClient api; private final McpAdapterProperties properties; private final AdapterSecurityProperties security;
 private final RedisStateService state; private final CryptoService crypto; private final DownstreamAuthService auth;
 public SilentAuthWorkflowService(BusinessApiClient api,McpAdapterProperties properties,AdapterSecurityProperties security,RedisStateService state,CryptoService crypto,DownstreamAuthService auth){this.api=api;this.properties=properties;this.security=security;this.state=state;this.crypto=crypto;this.auth=auth;}

 /** 发短信并建立与微信主体、原系统用户和三要素摘要绑定的一次性 Challenge。 */
 public Map<String,Object> start(String name,String mobile,String idCard,RequestContext context){
  DownstreamSession session=requireSession(context); long now=System.currentTimeMillis();
  SilentAuthChallenge active=state.active(context.principal().principalHash());
  if(active!=null&&(needsReconciliation(active.status())||active.expiresAt()>now&&Set.of(ChallengeStatus.PREPARING,ChallengeStatus.SMS_SENT,ChallengeStatus.SMS_VERIFIED,ChallengeStatus.APPLICATION_CREATED).contains(active.status()))){return publicChallenge(active,true);}
  String id=UUID.randomUUID().toString(); long expires=now+security.getChallenge().getTtl().toMillis();
  SilentAuthChallenge preparing=new SilentAuthChallenge(1,id,context.principal().principalHash(),session.userId(),session.tenantId(),null,crypto.encrypt(mobile),crypto.hash(mobile),SensitiveDataMasker.maskPhone(mobile),crypto.hash(name+"|"+idCard),ChallengeStatus.PREPARING,0,now,expires,null);
  state.saveChallenge(preparing);
  try{
   Map<String,Object> body=new LinkedHashMap<>();body.put("name",name);body.put("mobileNo",mobile);body.put("idCardNo",idCard);
   var p=properties.getTools().getPsnSilentAuth(); Object response=api.request(p.getTelecom3FactorsMethod(),p.getTelecom3FactorsPath(),Map.of(),body,Map.of(),session);
   String flow=telecomFlowId(response);
   SilentAuthChallenge sent=preparing.withFlow(crypto.encrypt(flow),ChallengeStatus.SMS_SENT);state.saveChallenge(sent);return publicChallenge(sent,false);
  }catch(RuntimeException e){state.saveChallenge(preparing.withStatus(ChallengeStatus.FAILED,safeMessage(e)));throw e;}
 }

 /**
  * 在分布式锁内恢复并推进工作流。Challenge 的主体、下游用户和租户必须同时匹配，
  * 任一不一致都按越权处理，不向调用者泄露 Challenge 属于谁。
  */
 public Map<String,Object> finish(String challengeId,String authCode,RequestContext context){
  String owner=state.tryLock(challengeId);if(owner==null)throw tool("WORKFLOW_BUSY","该授权流程正在处理中",true);
  try{
   DownstreamSession session=requireSession(context);SilentAuthChallenge c=state.challenge(challengeId);
   if(c==null)throw tool("CHALLENGE_NOT_FOUND","授权 Challenge 不存在",false);
   if(!c.principalHash().equals(context.principal().principalHash())||!Objects.equals(c.downstreamUserId(),session.userId())||!Objects.equals(c.tenantId(),session.tenantId()))throw tool("CHALLENGE_PERMISSION_DENIED","当前微信用户不能使用该 Challenge",false);
   if(c.expiresAt()<=System.currentTimeMillis()&&!needsReconciliation(c.status())&&c.status()!=ChallengeStatus.VERIFIED){c=c.withStatus(ChallengeStatus.EXPIRED,"Challenge 已过期");state.saveChallenge(c);throw tool("CHALLENGE_EXPIRED","短信 Challenge 已过期",false);}
   if(c.status()==ChallengeStatus.LOCKED)throw tool("CHALLENGE_LOCKED","验证码错误次数过多",false);
   if(c.status()==ChallengeStatus.VERIFIED)return result(c,session,true);
   var p=properties.getTools().getPsnSilentAuth();String flow=crypto.decrypt(c.encryptedFlowId());String mobile=crypto.decrypt(c.encryptedMobile());
   if(c.status()==ChallengeStatus.SMS_SENT||c.status()==ChallengeStatus.RETRYABLE_FAILURE){
    try{Object r=api.request(p.getSmsVerifyMethod(),p.getSmsVerifyPath(),Map.of(),Map.of("flowId",flow,"authCode",authCode),Map.of(),session);BusinessResponseUtils.unwrap(r);c=c.withStatus(ChallengeStatus.SMS_VERIFIED,null);state.saveChallenge(c);}
    catch(RuntimeException e){ChallengeStatus next=c.failedAttempts()+1>=security.getChallenge().getMaxCodeAttempts()?ChallengeStatus.LOCKED:ChallengeStatus.SMS_SENT;c=c.failedAttempt(next,safeMessage(e));state.saveChallenge(c);throw tool(next==ChallengeStatus.LOCKED?"CHALLENGE_LOCKED":"SMS_CODE_INVALID","短信验证码校验失败",false);}
   }
   if(c.status()==ChallengeStatus.SMS_VERIFIED){
    requireNoPendingApplication(p,session);
    // 先落盘再发送，覆盖下游已写入但响应丢失、或本进程中途退出的窗口。
    c=c.withStatus(ChallengeStatus.APPLICATION_UNKNOWN,null);state.saveChallenge(c);
    try{
     Object r=api.request(p.getPsnAuthMethod(),p.getPsnAuthPath(),Map.of(),Map.of("userId",session.userId(),"mobile",mobile),Map.of(),session);
     BusinessResponseUtils.unwrap(r);
    }catch(RuntimeException e){c=c.withStatus(ChallengeStatus.APPLICATION_UNKNOWN,safeMessage(e));state.saveChallenge(c);return result(c,session,false);}
    c=c.withStatus(ChallengeStatus.APPLICATION_CREATED,null);state.saveChallenge(c);
   }
   if(c.status()==ChallengeStatus.APPLICATION_CREATED){
    c=c.withStatus(ChallengeStatus.APPROVAL_PENDING,null);state.saveChallenge(c);
    try{
     Object r=api.request(p.getPsnAuthApproveMethod(),p.getPsnAuthApprovePath(),Map.of("userId",session.userId(),"authId",flow),null,Map.of(),session);
     Object updated=BusinessResponseUtils.unwrap(r);
     if(!(updated instanceof Number count)||count.intValue()!=1)throw tool("APPROVAL_NOT_CONFIRMED","批准接口未确认更新一条授权记录",false);
    }catch(RuntimeException e){c=c.withStatus(ChallengeStatus.APPROVAL_UNKNOWN,safeMessage(e));state.saveChallenge(c);return result(c,session,false);}
    c=c.withStatus(ChallengeStatus.APPROVED,null);state.saveChallenge(c);
   }
   if(needsReconciliation(c.status()))return verifyAuthorization(c,p,session,flow);
   return result(c,session,c.status()==ChallengeStatus.VERIFIED);
  }finally{state.unlock(challengeId,owner);}
 }
 private DownstreamSession requireSession(RequestContext c){DownstreamSession s=auth.current(c);if(s==null)throw tool("DOWNSTREAM_AUTH_REQUIRED","请先调用登录工具绑定当前微信账号",false);return s;}
 private Object readAuthed(McpAdapterProperties.PsnSilentAuth p,DownstreamSession s){return BusinessResponseUtils.unwrap(api.request(p.getPsnAuthedMethod(),p.getPsnAuthedPath(),Map.of("userId",s.userId()),null,Map.of(),s));}
 /** 旧批准接口会选择最新待批准记录；已有待批准记录时不再创建，交由原系统处理。 */
 private void requireNoPendingApplication(McpAdapterProperties.PsnSilentAuth p,DownstreamSession session){
  Object records=BusinessResponseUtils.unwrap(api.request(p.getPsnAuthListMethod(),p.getPsnAuthListPath(),Map.of("userId",session.userId(),"status",1),null,Map.of(),session));
  if(!(records instanceof List<?> list))throw tool("DOWNSTREAM_RESPONSE_INVALID","无法确认是否存在待批准授权记录",true);
  if(!list.isEmpty())throw tool("PENDING_APPLICATION_EXISTS","当前用户存在待批准授权记录，请先在原系统核对并处理",false);
 }
 private boolean needsReconciliation(ChallengeStatus status){
  return Set.of(ChallengeStatus.APPLICATION_UNKNOWN,ChallengeStatus.APPROVAL_PENDING,ChallengeStatus.APPROVAL_UNKNOWN,ChallengeStatus.APPROVED,ChallengeStatus.VERIFY_PENDING).contains(status);
 }
 /** 只在本次 flow 已批准且当前授权有效时确认完成，历史授权不能证明本次成功。 */
 private Map<String,Object> verifyAuthorization(SilentAuthChallenge c,McpAdapterProperties.PsnSilentAuth p,DownstreamSession session,String flow){
  Object authed=null,latest=null;
  try{
   authed=readAuthed(p,session);
   latest=BusinessResponseUtils.unwrap(api.request(p.getPsnAuthLatestMethod(),p.getPsnAuthLatestPath(),Map.of("userId",session.userId()),null,Map.of(),session));
  }catch(RuntimeException e){
   // 查询失败不改变创建/批准结果不确定的事实，也不允许重新发送写请求。
   c=c.withStatus(c.status(),safeMessage(e));
  }
  if(Boolean.TRUE.equals(authed)&&latest instanceof Map<?,?> record&&flow.equals(record.get("authId"))&&"2".equals(String.valueOf(record.get("status")))){
   c=c.withStatus(ChallengeStatus.VERIFIED,null);state.saveChallenge(c);
   Map<String,Object> out=result(c,session,true);out.put("authed",true);out.put("latestAuth",SensitiveDataMasker.sanitize(latest));return out;
  }
  if(c.status()==ChallengeStatus.APPROVED)c=c.withStatus(ChallengeStatus.VERIFY_PENDING,null);
  state.saveChallenge(c);return result(c,session,false);
 }
 private String telecomFlowId(Object response){
  Object data=BusinessResponseUtils.unwrap(response);
  if(data==null&&response instanceof Map<?,?>wrapper){
   Object message=wrapper.get("msg");
   if(message instanceof String value&&value.matches("\\d{8,64}"))return value;
  }
  return flowId(data);
 }

 private String flowId(Object v){if(v instanceof String s&&!s.isBlank())return s;if(v instanceof Map<?,?>m)for(String k:List.of("flowId","authId","id")){if(m.get(k)!=null)return String.valueOf(m.get(k));}throw tool("DOWNSTREAM_RESPONSE_INVALID","三要素接口未返回 flowId",false);}
 private Map<String,Object> publicChallenge(SilentAuthChallenge c,boolean reused){Map<String,Object> m=new LinkedHashMap<>();m.put("challengeId",c.challengeId());m.put("mobile",c.maskedMobile());m.put("expiresAtMillis",c.expiresAt());m.put("status",c.status().name());m.put("reused",reused);return m;}
 private Map<String,Object> result(SilentAuthChallenge c,DownstreamSession s,boolean complete){Map<String,Object>m=new LinkedHashMap<>();m.put("challengeId",c.challengeId());m.put("status",c.status().name());m.put("completed",complete);m.put("userId",s.userId());m.put("mobile",c.maskedMobile());if(c.status()==ChallengeStatus.APPLICATION_UNKNOWN)m.put("message","创建授权的结果不确定，系统不会重复提交或自动批准。请在原系统核对并处理待批准记录；重试 finish 只核验本次流程。");else if(needsReconciliation(c.status()))m.put("message","尚未确认本次流程授权成功，请稍后重试 finish 核验；系统不会重复批准。持续未确认时请在原系统核对授权记录。");return m;}
 private String safeMessage(Throwable e){return e.getClass().getSimpleName();}
 private McpToolException tool(String code,String message,boolean retry){return new McpToolException(code,message,"请按当前微信会话重新查询或重试。",retry);}
}
