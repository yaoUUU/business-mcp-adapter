package com.earchives.mcp.adapter.service;

import com.earchives.mcp.adapter.auth.RequestContext;
import com.earchives.mcp.adapter.state.RedisStateService;
import com.earchives.mcp.adapter.config.McpAdapterProperties;
import com.earchives.mcp.adapter.exception.DownstreamException;
import com.earchives.mcp.adapter.exception.McpToolException;
import com.earchives.mcp.adapter.tool.ArgumentUtils;
import com.earchives.mcp.adapter.tool.BusinessResponseUtils;
import com.earchives.mcp.adapter.tool.SensitiveDataMasker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;



/**
 * 原系统动态登录服务。
 *
 * <p>微信用户可以把原系统账号密码交给 OpenClaw，OpenClaw 再把它们作为工具参数
 * 传给 adapter。adapter 登录原系统后缓存 accessToken/clientId/userId，后续同一个
 * MCP token 下的工具调用就不需要再手填 BUSINESS_API_TOKEN 或 userId。</p>
 */
@Service
public class DownstreamAuthService {

    private static final String PLACEHOLDER_TOKEN = "原系统登录 token";

    private final BusinessApiClient businessApiClient;
    private final McpAdapterProperties properties;
    private final Clock clock;
    /** Redis 是唯一会话存储，禁止共享 MCP Token 对应一个进程内用户。 */
    private final RedisStateService stateService;

    @Autowired
    public DownstreamAuthService(BusinessApiClient businessApiClient, McpAdapterProperties properties,
                                 RedisStateService stateService) {
        this(businessApiClient, properties, stateService, Clock.systemUTC());
    }

    DownstreamAuthService(BusinessApiClient businessApiClient, McpAdapterProperties properties, RedisStateService stateService, Clock clock) {
        this.businessApiClient = businessApiClient;
        this.properties = properties;
        this.clock = clock;
        this.stateService = stateService;
    }

    /**
     * 根据工具参数或已缓存会话解析原系统登录态。
     */
    public DownstreamSession resolve(Map<String, Object> arguments, RequestContext requestContext) {
        String username = ArgumentUtils.optionalString(arguments, "username");
        String password = ArgumentUtils.optionalString(arguments, "password");
        if (StringUtils.hasText(username) || StringUtils.hasText(password)) {
            if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                throw new McpToolException("INVALID_ARGUMENT", "原系统账号和密码必须同时提供",
                    "请同时传入 username 和 password，或者先调用 ca_psn_silent_auth_login 完成登录。", false);
            }
            return login(arguments, requestContext);
        }

        DownstreamSession cached = current(requestContext);
        if (cached != null) {
            return cached;
        }

        DownstreamSession configured = configuredTokenSession(requestContext);
        if (configured != null) {
            return configured;
        }

        throw new McpToolException("DOWNSTREAM_AUTH_REQUIRED", "尚未登录原系统",
            "请先告诉 OpenClaw 原系统账号密码，或调用 ca_psn_silent_auth_login。adapter 不再需要启动时写死 auth-token。", false);
    }

    /**
     * 使用账号密码登录原系统并缓存当前用户上下文。
     */
    public DownstreamSession login(Map<String, Object> arguments, RequestContext requestContext) {
        String username = ArgumentUtils.requiredString(arguments, "username", "your_account");
        String password = ArgumentUtils.requiredString(arguments, "password", "your_password");
        String tenantId = firstText(ArgumentUtils.optionalString(arguments, "tenantId"),
            properties.getDownstream().getLoginTenantId());
        String clientId = firstText(ArgumentUtils.optionalString(arguments, "clientId"),
            properties.getDownstream().getLoginClientId());
        String grantType = firstText(ArgumentUtils.optionalString(arguments, "grantType"),
            properties.getDownstream().getLoginGrantType());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId);
        body.put("grantType", grantType);
        body.put("tenantId", tenantId);
        body.put("username", username);
        body.put("password", password);
        putIfText(body, "code", ArgumentUtils.optionalString(arguments, "code"));
        putIfText(body, "uuid", ArgumentUtils.optionalString(arguments, "uuid"));

        try {
            Object loginResponse = businessApiClient.requestWithoutAuth("POST",
                properties.getDownstream().getLoginPath(), Map.of(), body, Map.of());
            Object loginData = BusinessResponseUtils.unwrap(loginResponse);
            String accessToken = firstString(loginData, "access_token", "accessToken", "token");
            String loginClientId = firstText(firstString(loginData, "client_id", "clientId"), clientId);
            Long expireIn = firstLong(loginData, "expire_in", "expireIn");
            if (!StringUtils.hasText(accessToken)) {
                throw new McpToolException("DOWNSTREAM_LOGIN_INVALID",
                    "原系统登录成功响应中没有 access_token",
                    "请检查 /auth/login 返回结构。响应：" + SensitiveDataMasker.sanitize(loginData), false);
            }

            DownstreamSession session = sessionFromToken(accessToken, loginClientId, tenantId, expireIn);
            stateService.saveSession(sessionKey(requestContext), session);
            return session;
        } catch (DownstreamException e) {
            throw new McpToolException("DOWNSTREAM_LOGIN_ERROR", "原系统登录接口调用失败",
                "请检查原系统地址、账号密码、租户和登录 clientId。", e.getStatusCode() == 0);
        }
    }

    /**
     * 返回当前 MCP 客户端缓存的原系统会话。
     */
    public DownstreamSession current(RequestContext requestContext) {
        DownstreamSession session = stateService.loadSession(sessionKey(requestContext));
        if (session == null) {
            return null;
        }
        if (session.expired(clock.millis())) {
            stateService.deleteSession(sessionKey(requestContext));
            return null;
        }
        return session;
    }

    /**
     * 生成可返回给 OpenClaw/微信展示的登录信息，不包含 token/password。
     */
    public Object publicSessionInfo(DownstreamSession session) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("userId", session.userId());
        info.put("username", session.username());
        info.put("nickname", session.nickname());
        info.put("mobile", SensitiveDataMasker.maskPhone(session.mobile()));
        info.put("tenantId", session.tenantId());
        info.put("clientId", session.clientId());
        info.put("expiresAtMillis", session.expiresAtMillis());
        return SensitiveDataMasker.sanitize(info);
    }

    private DownstreamSession configuredTokenSession(RequestContext requestContext) {
        String token = properties.getDownstream().getAuthToken();
        if (!StringUtils.hasText(token) || PLACEHOLDER_TOKEN.equals(token.trim())) {
            return null;
        }
        String clientId = properties.getDownstream().getClientId();
        DownstreamSession session = sessionFromToken(token, clientId, properties.getDownstream().getLoginTenantId(), null);
        stateService.saveSession(sessionKey(requestContext), session);
        return session;
    }

    private DownstreamSession sessionFromToken(String accessToken, String clientId, String tenantId, Long expireInSeconds) {
        Object userInfoResponse;
        try {
            userInfoResponse = businessApiClient.requestWithAuth("GET",
                properties.getDownstream().getUserInfoPath(), Map.of(), null, Map.of(), accessToken, clientId);
        } catch (DownstreamException e) {
            throw new McpToolException("DOWNSTREAM_USER_INFO_ERROR", "原系统当前用户信息接口调用失败",
                "请检查 /system/user/getInfo 是否可用，以及登录 token/clientId 是否有效。", e.getStatusCode() == 0);
        }
        Object userInfo = BusinessResponseUtils.unwrap(userInfoResponse);
        Object user = valueAt(userInfo, "user");
        Long userId = firstLong(user, "userId", "user_id", "id");
        if (userId == null) {
            throw new McpToolException("DOWNSTREAM_USER_INVALID",
                "原系统用户信息响应中没有 userId",
                "请检查 /system/user/getInfo 是否可用。响应：" + SensitiveDataMasker.sanitize(userInfo), false);
        }
        String username = firstString(user, "userName", "username", "user_name");
        String nickname = firstString(user, "nickName", "nickname", "nick_name");
        String mobile = firstString(user, "phonenumber", "phoneNumber", "mobile", "mobileNo");
        String userTenantId = firstText(firstString(user, "tenantId", "tenant_id"), tenantId);
        long expiresAt = expiresAt(expireInSeconds);
        return new DownstreamSession(accessToken, clientId, userId, username, nickname, mobile, userTenantId, expiresAt);
    }

    private long expiresAt(Long expireInSeconds) {
        long now = clock.millis();
        if (expireInSeconds != null && expireInSeconds > 0) {
            long effectiveSeconds = Math.max(30L, expireInSeconds - 60L);
            return now + effectiveSeconds * 1000L;
        }
        long ttl = Math.max(60_000L, properties.getDownstream().getLoginCacheTtl().toMillis());
        return now + ttl;
    }

    private String sessionKey(RequestContext requestContext) {
        return requestContext.principal().principalHash();
    }

    private void putIfText(Map<String, Object> body, String key, String value) {
        if (StringUtils.hasText(value)) {
            body.put(key, value);
        }
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first.trim() : second;
    }

    private Object valueAt(Object value, String key) {
        if (value instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }

    private String firstString(Object value, String... names) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        for (String name : names) {
            Object raw = map.get(name);
            if (raw != null && StringUtils.hasText(String.valueOf(raw))) {
                return String.valueOf(raw).trim();
            }
        }
        return null;
    }

    private Long firstLong(Object value, String... names) {
        String text = firstString(value, names);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
