package com.earchives.mcp.adapter.auth;

import com.earchives.mcp.adapter.config.AdapterSecurityProperties;
import com.earchives.mcp.adapter.exception.McpAuthException;
import com.earchives.mcp.adapter.security.CryptoService;
import com.earchives.mcp.adapter.state.RedisStateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;

/**
 * 校验 OpenClaw 为个人微信请求生成的 HMAC-SHA256 签名。
 *
 * <p>签名覆盖渠道、微信用户、MCP 会话、时间戳、nonce 和原始 JSON 正文摘要，因而代理
 * 无法在不掌握共享密钥的情况下替换工具参数或用户。验证成功后才登记 nonce：SET NX + TTL
 * 使同一签名只能使用一次。原始微信用户 ID 仅用于本次摘要计算，不进入日志。</p>
 */
@Service
public class WechatSignatureService {
 public static final String CHANNEL="X-OpenClaw-Channel",USER="X-OpenClaw-User-Id",TIMESTAMP="X-OpenClaw-Timestamp",NONCE="X-OpenClaw-Nonce",SIGNATURE="X-OpenClaw-Signature",SESSION="Mcp-Session-Id";
 private final AdapterSecurityProperties properties; private final RedisStateService state; private final CryptoService crypto; private final Clock clock=Clock.systemUTC();
 public WechatSignatureService(AdapterSecurityProperties properties,RedisStateService state,CryptoService crypto){this.properties=properties;this.state=state;this.crypto=crypto;}

 /** 验证 tools/call 请求并返回不可由模型 arguments 伪造的个人微信主体。 */
 public ChannelPrincipal verify(HttpServletRequest request,byte[] body,AuthContext client){
  String channel=required(request,CHANNEL), user=required(request,USER), timestamp=required(request,TIMESTAMP), nonce=required(request,NONCE), supplied=required(request,SIGNATURE), session=text(request.getHeader(SESSION));
  if(!"wechat".equals(channel)) throw auth("CHANNEL_NOT_ALLOWED","仅允许个人微信渠道 wechat");
  if(user.length()>256||nonce.length()>128||nonce.length()<16) throw auth("INVALID_CHANNEL_IDENTITY","微信用户标识或 nonce 长度非法");
  long seconds; try{seconds=Long.parseLong(timestamp);}catch(NumberFormatException e){throw auth("INVALID_SIGNATURE_TIMESTAMP","签名时间戳必须是 Unix 秒");}
  long skew=Math.abs(clock.instant().getEpochSecond()-seconds);
  if(skew>properties.getSecurity().getSignatureSkew().toSeconds()) throw auth("SIGNATURE_EXPIRED","签名已过期或客户端时钟偏差过大");
  if(!StringUtils.hasText(client.channelSigningSecret())) throw auth("SIGNING_SECRET_MISSING","当前 MCP 客户端没有配置个人微信签名密钥");
  String bodyHash=sha256(body);
  String canonical=String.join("\n","v1","POST","/mcp",channel,user,session,timestamp,nonce,bodyHash);
  String expected="v1="+hmac(client.channelSigningSecret(),canonical);
  if(!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII))) throw auth("INVALID_CHANNEL_SIGNATURE","个人微信请求签名无效");
  if(!state.claimNonce(client.mcpClientId(),nonce)) throw auth("SIGNATURE_REPLAYED","该个人微信请求签名已使用");
  return new ChannelPrincipal(channel,user,session,crypto.hash(client.mcpClientId()+"|"+channel+"|"+user));
 }
 private String required(HttpServletRequest r,String name){String v=r.getHeader(name);if(!StringUtils.hasText(v))throw auth("CHANNEL_IDENTITY_REQUIRED","tools/call 缺少请求头 "+name);return v.trim();}
 private String text(String v){return v==null?"":v.trim();}
 private String sha256(byte[] body){try{return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(body));}catch(Exception e){throw new IllegalStateException(e);}}
 private String hmac(String secret,String value){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("HMAC-SHA256 不可用",e);}}
 private McpAuthException auth(String code,String message){return new McpAuthException(code,message,"请检查 OpenClaw 个人微信身份头、时间同步和 HMAC 配置。");}
}