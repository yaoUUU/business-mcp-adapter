package com.earchives.mcp.adapter.security;
import com.earchives.mcp.adapter.config.AdapterSecurityProperties;import org.junit.jupiter.api.Test;import java.util.Base64;import static org.junit.jupiter.api.Assertions.*;
/**
 * 验证 Redis 敏感字段加密的核心安全性质：可逆、随机 IV 与认证标签防篡改。
 */
class CryptoServiceTest {
 private CryptoService service(){AdapterSecurityProperties p=new AdapterSecurityProperties();p.getSecurity().setDataEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));CryptoService s=new CryptoService(p);s.validateKey();return s;}
 /** 相同明文必须产生不同密文，证明每次使用新的随机 IV。 */
 @Test void encryptUsesRandomIvAndDecrypts(){CryptoService s=service();String a=s.encrypt("secret-token"),b=s.encrypt("secret-token");assertNotEquals(a,b);assertEquals("secret-token",s.decrypt(a));}
 /** 修改任意密文字节都必须触发 GCM 认证失败，而不是返回损坏的 token。 */
 @Test void tamperedCiphertextIsRejected(){CryptoService s=service();byte[] bytes=Base64.getUrlDecoder().decode(s.encrypt("flow-id"));bytes[bytes.length-1]^=1;String tampered=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);assertThrows(IllegalStateException.class,()->s.decrypt(tampered));}
}