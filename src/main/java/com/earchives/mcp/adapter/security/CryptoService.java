package com.earchives.mcp.adapter.security;

import com.earchives.mcp.adapter.config.AdapterSecurityProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Redis 敏感字段的 AES-256-GCM 加密器与不可逆摘要工具。
 *
 * <p>每次加密使用 12 字节随机 IV；GCM 的 128 位认证标签同时校验机密性和完整性。
 * 密文格式为 Base64URL(IV || ciphertext || tag)。密钥只来自环境配置，永不写入 Redis、
 * 响应或日志。该组件是无共享可变状态的，Cipher 每次调用创建，因此可安全并发使用。</p>
 */
@Component
public class CryptoService {
    private final AdapterSecurityProperties properties;
    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec key;
    public CryptoService(AdapterSecurityProperties properties){this.properties=properties;}

    /** 启动时严格验证 32 字节密钥，防止服务以明文或弱密钥状态运行。 */
    @PostConstruct
    public void validateKey(){
        String configured=properties.getSecurity().getDataEncryptionKey();
        if(!StringUtils.hasText(configured)) throw new IllegalStateException("BUSINESS_MCP_DATA_KEY 必须配置");
        byte[] bytes;
        try{bytes=Base64.getDecoder().decode(configured);}catch(IllegalArgumentException e){throw new IllegalStateException("BUSINESS_MCP_DATA_KEY 必须是 Base64",e);}
        if(bytes.length!=32) throw new IllegalStateException("BUSINESS_MCP_DATA_KEY 解码后必须为 32 字节");
        key=new SecretKeySpec(bytes,"AES");
    }

    /** 加密 access token、flowId 等禁止明文落入 Redis 的值。 */
    public String encrypt(String plaintext){
        try{
            byte[] iv=new byte[12]; random.nextBytes(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));
            byte[] encrypted=cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed=new byte[iv.length+encrypted.length];
            System.arraycopy(iv,0,packed,0,iv.length); System.arraycopy(encrypted,0,packed,iv.length,encrypted.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
        }catch(Exception e){throw new IllegalStateException("敏感状态加密失败",e);}
    }

    /** 解密 Redis 中的敏感值；认证标签不匹配时失败关闭，不返回损坏数据。 */
    public String decrypt(String ciphertext){
        try{
            byte[] packed=Base64.getUrlDecoder().decode(ciphertext);
            if(packed.length<29) throw new IllegalArgumentException("密文长度非法");
            byte[] iv=java.util.Arrays.copyOfRange(packed,0,12), body=java.util.Arrays.copyOfRange(packed,12,packed.length);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,iv));
            return new String(cipher.doFinal(body),StandardCharsets.UTF_8);
        }catch(Exception e){throw new IllegalStateException("敏感状态解密或完整性校验失败",e);}
    }

    /** 生成不可逆、无填充 Base64URL SHA-256 摘要，供主体 Key 和审计使用。 */
    public String hash(String value){
        try{return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("SHA-256 不可用",e);}
    }
}