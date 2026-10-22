package com.earchives.mcp.adapter.service;
import com.earchives.mcp.adapter.auth.*;import com.earchives.mcp.adapter.config.*;import com.earchives.mcp.adapter.exception.McpToolException;import com.earchives.mcp.adapter.security.CryptoService;import com.earchives.mcp.adapter.state.*;import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
/**
 * 多个人微信共用同一 MCP Token 时的对象级隔离测试。
 *
 * <p>测试不依赖真实 CA 或 Redis，专门验证安全判断发生在任何下游写操作之前。</p>
 */
class SilentAuthWorkflowServiceTest{
 /** A 微信尝试消费属于 B 的 Challenge 时必须失败，并且不得调用任何下游接口。 */
 @Test void challengeCannotCrossWechatPrincipal(){
  BusinessApiClient api=mock(BusinessApiClient.class);RedisStateService state=mock(RedisStateService.class);DownstreamAuthService auth=mock(DownstreamAuthService.class);CryptoService crypto=mock(CryptoService.class);
  McpAdapterProperties p=new McpAdapterProperties();AdapterSecurityProperties security=new AdapterSecurityProperties();SilentAuthWorkflowService service=new SilentAuthWorkflowService(api,p,security,state,crypto,auth);
  AuthContext client=new AuthContext("shared-client","test","same-token-hash","signing-secret",Set.of("*"));RequestContext userA=new RequestContext("request-A",client,new ChannelPrincipal("wechat","wx-A","session","principal-A"));
  DownstreamSession sessionA=new DownstreamSession("token-A","client",100L,"a","A","13700000000","000000",System.currentTimeMillis()+60000);when(auth.current(userA)).thenReturn(sessionA);when(state.tryLock("challenge-B")).thenReturn("owner");when(state.challenge("challenge-B")).thenReturn(new SilentAuthChallenge(1,"challenge-B","principal-B",200L,"000000","flow","mobile","mh","137****0000","ih",ChallengeStatus.SMS_SENT,0,System.currentTimeMillis(),System.currentTimeMillis()+60000,null));
  McpToolException error=assertThrows(McpToolException.class,()->service.finish("challenge-B","123456",userA));assertEquals("CHALLENGE_PERMISSION_DENIED",error.getErrorCode());verifyNoInteractions(api);verify(state).unlock("challenge-B","owner");
 }
 /** 同一 MCP 客户端下的两个微信主体必须使用不同 Redis 会话 Key。 */
 @Test void downstreamSessionsAreKeyedByWechatPrincipal(){
  RedisStateService state=mock(RedisStateService.class);DownstreamAuthService service=new DownstreamAuthService(mock(BusinessApiClient.class),new McpAdapterProperties(),state);
  AuthContext client=new AuthContext("shared","test","same-token","secret",Set.of("*"));RequestContext a=new RequestContext("a",client,new ChannelPrincipal("wechat","wx-A","","hash-A"));RequestContext b=new RequestContext("b",client,new ChannelPrincipal("wechat","wx-B","","hash-B"));service.current(a);service.current(b);verify(state).loadSession("hash-A");verify(state).loadSession("hash-B");
 }
 /** Legacy R<String> overload stores a successful flow id in msg with null data. */
 @Test void startAcceptsLegacyFlowIdInSuccessMessage(){
  BusinessApiClient api=mock(BusinessApiClient.class);RedisStateService state=mock(RedisStateService.class);DownstreamAuthService auth=mock(DownstreamAuthService.class);CryptoService crypto=mock(CryptoService.class);
  McpAdapterProperties p=new McpAdapterProperties();AdapterSecurityProperties security=new AdapterSecurityProperties();SilentAuthWorkflowService service=new SilentAuthWorkflowService(api,p,security,state,crypto,auth);
  AuthContext client=new AuthContext("client","test","token","secret",Set.of("*"));RequestContext context=new RequestContext("request",client,new ChannelPrincipal("wechat","wx-A","","principal-A"));
  DownstreamSession session=new DownstreamSession("token-A","client",100L,"a","A","13700007716","000000",System.currentTimeMillis()+60000);when(auth.current(context)).thenReturn(session);
  when(crypto.encrypt(anyString())).thenAnswer(invocation->"encrypted:"+invocation.getArgument(0));when(crypto.hash(anyString())).thenReturn("hash");
  Map<String,Object> response=new LinkedHashMap<>();response.put("code",200);response.put("msg","4204319921297427518");response.put("data",null);
  when(api.request(anyString(),anyString(),anyMap(),any(),anyMap(),same(session))).thenReturn(response);
  Map<String,Object> result=service.start("name","13700007716","330000000000000000",context);
  assertEquals("SMS_SENT",result.get("status"));assertEquals(false,result.get("reused"));
  verify(crypto).encrypt("4204319921297427518");
 }
 @Test void finishUsesLegacyCreateAndBindsFlowDuringApproval(){
  WorkflowFixture f=new WorkflowFixture();
  Map<String,Object> result=f.finish();
  assertEquals("VERIFIED",result.get("status"));assertEquals(true,result.get("completed"));
  var ordered=inOrder(f.api);
  ordered.verify(f.api).request(eq("POST"),eq(f.p.getSmsVerifyPath()),anyMap(),eq(Map.of("flowId",f.flow,"authCode","123456")),anyMap(),same(f.session));
  ordered.verify(f.api).request(eq("GET"),eq(f.p.getPsnAuthListPath()),eq(Map.of("userId",100L,"status",1)),isNull(),anyMap(),same(f.session));
  ordered.verify(f.api).request(eq("POST"),eq("/ca/local/auth/psn/auth"),anyMap(),eq(Map.of("userId",100L,"mobile","13700007716")),anyMap(),same(f.session));
  ordered.verify(f.api).request(eq("POST"),eq(f.p.getPsnAuthApprovePath()),eq(Map.of("userId",100L,"authId",f.flow)),isNull(),anyMap(),same(f.session));
  assertFalse(String.valueOf(result).contains(f.flow));
 }
 @Test void creationTimeoutIsPersistedAndNeverReplayed(){
  WorkflowFixture f=new WorkflowFixture();f.createFailure=new com.earchives.mcp.adapter.exception.DownstreamException("timeout",0,null,null);
  f.latest=Map.of("authId","historical-flow","status",2);
  assertEquals("APPLICATION_UNKNOWN",f.finish().get("status"));
  assertEquals(false,f.finish().get("completed"));
  verify(f.api,times(1)).request(eq("POST"),eq(f.p.getPsnAuthPath()),anyMap(),any(),anyMap(),same(f.session));
  verify(f.api,never()).request(anyString(),eq(f.p.getPsnAuthApprovePath()),anyMap(),any(),anyMap(),any());
 }
 @Test void crashAfterDispatchMarkerDoesNotReplayCreate(){
  WorkflowFixture f=new WorkflowFixture();f.current=f.current.withStatus(ChallengeStatus.APPLICATION_UNKNOWN,null);
  f.latest=Map.of("authId","historical-flow","status",2);
  assertEquals(false,f.finish().get("completed"));f.assertNoWrites();
 }
 @Test void pendingApplicationBlocksCreateAndApproval(){
  WorkflowFixture f=new WorkflowFixture();f.pending=List.of(Map.of("status",1));
  McpToolException error=assertThrows(McpToolException.class,f::finish);
  assertEquals("PENDING_APPLICATION_EXISTS",error.getErrorCode());
  verify(f.api,never()).request(eq("POST"),eq(f.p.getPsnAuthPath()),anyMap(),any(),anyMap(),any());
  verify(f.api,never()).request(anyString(),eq(f.p.getPsnAuthApprovePath()),anyMap(),any(),anyMap(),any());
 }
 @Test void historicalAuthorizationDoesNotConfirmThisFlow(){
  WorkflowFixture f=new WorkflowFixture();f.latest=Map.of("authId","old-flow","status",2);
  assertEquals("VERIFY_PENDING",f.finish().get("status"));assertEquals(false,f.finish().get("completed"));
  verify(f.api,times(1)).request(eq("POST"),eq(f.p.getPsnAuthPath()),anyMap(),any(),anyMap(),any());
  verify(f.api,times(1)).request(anyString(),eq(f.p.getPsnAuthApprovePath()),anyMap(),any(),anyMap(),any());
 }
 @Test void matchingFlowMustAlsoBeApprovedAndCurrentlyValid(){
  WorkflowFixture f=new WorkflowFixture();f.latest=Map.of("authId",f.flow,"status",1);
  assertEquals(false,f.finish().get("completed"));
  f.latest=Map.of("authId",f.flow,"status",2);f.authed=false;
  assertEquals(false,f.finish().get("completed"));
  f.authed=true;assertEquals(true,f.finish().get("completed"));
 }
 @Test void approvalTimeoutRecoversOnlyByReadingMatchingFlow(){
  WorkflowFixture f=new WorkflowFixture();f.approveFailure=new com.earchives.mcp.adapter.exception.DownstreamException("timeout",0,null,null);
  assertEquals("APPROVAL_UNKNOWN",f.finish().get("status"));
  assertEquals(true,f.finish().get("completed"));
  verify(f.api,times(1)).request(anyString(),eq(f.p.getPsnAuthApprovePath()),anyMap(),any(),anyMap(),any());
 }
 @Test void approvalPendingSurvivesCrashAndChallengeExpiryForReadOnlyRecovery(){
  WorkflowFixture f=new WorkflowFixture();
  f.current=new SilentAuthChallenge(1,"challenge-A","principal-A",100L,"000000","enc-flow","enc-mobile","mh","137****7716","ih",ChallengeStatus.APPROVAL_PENDING,0,1,2,null);
  assertEquals(true,f.finish().get("completed"));f.assertNoWrites();
 }
 @Test void zeroUpdatedRowsIsNotReportedAsApproved(){
  WorkflowFixture f=new WorkflowFixture();f.updated=0;
  assertEquals("APPROVAL_UNKNOWN",f.finish().get("status"));
 }
 @Test void failedQueryDoesNotEraseUncertaintyOrReplayWrites(){
  WorkflowFixture f=new WorkflowFixture();f.current=f.current.withStatus(ChallengeStatus.APPLICATION_UNKNOWN,null);
  f.readFailure=new com.earchives.mcp.adapter.exception.DownstreamException("timeout",0,null,null);
  assertEquals("APPLICATION_UNKNOWN",f.finish().get("status"));f.assertNoWrites();
 }
 /** 模拟旧后端真实响应：创建/短信验证返回空 data，批准返回更新行数。 */
 private static class WorkflowFixture{
  final BusinessApiClient api=mock(BusinessApiClient.class);
  final RedisStateService state=mock(RedisStateService.class);
  final DownstreamAuthService auth=mock(DownstreamAuthService.class);
  final CryptoService crypto=mock(CryptoService.class);
  final McpAdapterProperties properties=new McpAdapterProperties();
  final McpAdapterProperties.PsnSilentAuth p=properties.getTools().getPsnSilentAuth();
  final String flow="4204319921297427518";
  final RequestContext context=new RequestContext("request",new AuthContext("client","test","token","secret",Set.of("*")),new ChannelPrincipal("wechat","wx-A","","principal-A"));
  final DownstreamSession session=new DownstreamSession("token-A","client",100L,"a","A","13700007716","000000",System.currentTimeMillis()+60000);
  final SilentAuthWorkflowService service=new SilentAuthWorkflowService(api,properties,new AdapterSecurityProperties(),state,crypto,auth);
  SilentAuthChallenge current=new SilentAuthChallenge(1,"challenge-A","principal-A",100L,"000000","enc-flow","enc-mobile","mh","137****7716","ih",ChallengeStatus.SMS_SENT,0,System.currentTimeMillis(),System.currentTimeMillis()+60000,null);
  Object pending=List.of();Object latest=Map.of("authId",flow,"status",2);boolean authed=true;int updated=1;
  RuntimeException createFailure,approveFailure,readFailure;
  WorkflowFixture(){
   when(auth.current(context)).thenReturn(session);when(state.tryLock("challenge-A")).thenReturn("owner");
   when(state.challenge("challenge-A")).thenAnswer(i->current);
   doAnswer(i->{current=i.getArgument(0);return null;}).when(state).saveChallenge(any());
   when(crypto.decrypt("enc-flow")).thenReturn(flow);when(crypto.decrypt("enc-mobile")).thenReturn("13700007716");
   when(api.request(anyString(),anyString(),anyMap(),nullable(Object.class),anyMap(),same(session))).thenAnswer(i->{
    String method=i.getArgument(0),path=i.getArgument(1);Object data=null;
    if(path.equals(p.getPsnAuthPath())&&method.equals("POST")){if(createFailure!=null)throw createFailure;}
    else if(path.equals(p.getPsnAuthApprovePath())){if(approveFailure!=null)throw approveFailure;data=updated;}
    else if(path.equals(p.getPsnAuthListPath()))data=pending;
    else if(path.equals(p.getPsnAuthedPath())){if(readFailure!=null)throw readFailure;data=authed;}
    else if(path.equals(p.getPsnAuthLatestPath()))data=latest;
    else if(!path.equals(p.getSmsVerifyPath()))throw new AssertionError("Unexpected request: "+path);
    Map<String,Object> response=new LinkedHashMap<>();response.put("code",200);response.put("data",data);return response;
   });
  }
  Map<String,Object> finish(){return service.finish("challenge-A","123456",context);}
  void assertNoWrites(){verify(api,never()).request(eq("POST"),anyString(),anyMap(),nullable(Object.class),anyMap(),any());}
 }
}
