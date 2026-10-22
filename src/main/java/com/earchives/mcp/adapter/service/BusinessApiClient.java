package com.earchives.mcp.adapter.service;

import com.earchives.mcp.adapter.config.McpAdapterProperties;
import com.earchives.mcp.adapter.exception.DownstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 原业务系统 HTTP 客户端。
 *
 * <p>个人静默签章授权工具不直接接入 e签宝 SDK，而是调用原系统已经存在的
 * /ca/local/... 接口。这样可以复用原系统的登录态、权限校验、CA 配置和业务审计，
 * adapter 只承担 OpenClaw MCP 到原系统 REST 的薄转换层。</p>
 */
@Component
public class BusinessApiClient {

    /*
     * 原系统要求 Authorization 使用 Bearer token，同时还会校验 clientid。
     * CLIENT_ID_PAYLOAD_KEYS 覆盖几种可能的 JWT 字段名，避免不同登录端字段命名不同。
     */
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CLIENT_ID_HEADER = "clientid";
    private static final String[] CLIENT_ID_PAYLOAD_KEYS = {"clientid", "clientId", "client_id"};
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final McpAdapterProperties properties;

    public BusinessApiClient(RestClient businessRestClient, McpAdapterProperties properties) {
        this.restClient = businessRestClient;
        this.properties = properties;
    }

    /**
     * 发起到原系统的请求。
     *
     * <p>所有个人静默签章授权工具都走这个方法，调用路径和 HTTP method 从
     * application.yml 的 business.mcp.tools.psn-silent-auth 读取，方便不同环境
     * 的原系统路径有差异时只改配置。</p>
     */
    public Object request(String method, String path, Map<String, ?> queryParams, Object body,
                          Map<String, ?> pathVariables) {
        String authToken = normalizeToken(properties.getDownstream().getAuthToken());
        String clientId = resolveClientId(authToken, properties.getDownstream().getClientId());
        return requestWithAuth(method, path, queryParams, body, pathVariables, authToken, clientId);
    }

    /**
     * 使用调用方指定的原系统登录态发起请求。
     *
     * <p>动态登录后得到的 token/clientId 会走这里，避免再依赖启动配置里的
     * BUSINESS_API_TOKEN。</p>
     */
    public Object request(String method, String path, Map<String, ?> queryParams, Object body,
                          Map<String, ?> pathVariables, DownstreamSession session) {
        return requestWithAuth(method, path, queryParams, body, pathVariables,
            session == null ? null : session.accessToken(),
            session == null ? null : session.clientId());
    }

    /**
     * 发起不带原系统登录态的请求，主要用于 /auth/login。
     */
    public Object requestWithoutAuth(String method, String path, Map<String, ?> queryParams, Object body,
                                     Map<String, ?> pathVariables) {
        return doRequest(method, path, queryParams, body, pathVariables, null, null);
    }

    /**
     * 发起带指定 token/clientId 的请求。
     */
    public Object requestWithAuth(String method, String path, Map<String, ?> queryParams, Object body,
                                  Map<String, ?> pathVariables, String authToken, String clientId) {
        String normalizedToken = normalizeToken(authToken);
        String effectiveClientId = resolveClientId(normalizedToken, clientId);
        return doRequest(method, path, queryParams, body, pathVariables, normalizedToken, effectiveClientId);
    }

    private Object doRequest(String method, String path, Map<String, ?> queryParams, Object body,
                             Map<String, ?> pathVariables, String authToken, String clientId) {
        HttpMethod httpMethod = HttpMethod.valueOf(method.toUpperCase());
        try {
            Map<String, Object> effectiveQueryParams = withClientIdQueryParam(queryParams, clientId);

            /*
             * URI 构建统一处理 query 和 pathVariables。
             * 当前个人静默签章接口主要使用 query 参数，但保留 pathVariables 方便以后
             * 接入同类原系统接口时不用改公共客户端。
             */
            RestClient.RequestBodySpec spec = restClient.method(httpMethod)
                .uri(builder -> {
                    var uriBuilder = builder.path(path);
                    if (!CollectionUtils.isEmpty(effectiveQueryParams)) {
                        effectiveQueryParams.forEach(uriBuilder::queryParam);
                    }
                    if (CollectionUtils.isEmpty(pathVariables)) {
                        return uriBuilder.build();
                    }
                    return uriBuilder.build(pathVariables);
                })
                .accept(MediaType.APPLICATION_JSON);

            applyDownstreamAuth(spec, authToken, clientId);

            // GET/DELETE 等请求不写 body，避免原系统网关或拦截器误判请求格式。
            if (body != null && supportsBody(httpMethod)) {
                spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }

            return spec.retrieve().body(Object.class);
        } catch (RestClientResponseException e) {
            throw new DownstreamException("下游业务系统返回错误", e.getStatusCode().value(), e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new DownstreamException("下游业务系统不可用", 0, null, e);
        }
    }

    /**
     * 判断 HTTP 方法是否允许携带 JSON body。
     */
    private boolean supportsBody(HttpMethod method) {
        return HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method) || HttpMethod.PATCH.equals(method);
    }

    /**
     * 原系统接口需要 Bearer token，并且安全拦截器会校验 clientid。
     *
     * <p>原系统会把请求头/请求参数中的 {@code clientid} 和 Sa-Token/JWT 里保存的
     * {@code clientid} 做严格比较。如果 adapter 发送了错误的 clientid，即使
     * Authorization 是刚登录拿到的 token，原系统也会按“token 无效”处理。因此这里
     * 优先从 JWT payload 解析真实 clientid，并同时写入请求头和 query 参数。</p>
     */
    private void applyDownstreamAuth(RestClient.RequestHeadersSpec<?> spec, String authToken, String clientId) {
        if (!StringUtils.hasText(authToken)) {
            return;
        }
        spec.header("Authorization", authorizationHeaderValue(authToken));

        /*
         * 原系统拦截器会读取请求头 clientid；之前出现过 clientid 写错导致
         * “token 无效”的情况，所以这里由 token 自动解析，尽量减少手工配置风险。
         */
        if (StringUtils.hasText(clientId)) {
            spec.header(CLIENT_ID_HEADER, clientId);
        }
    }

    /**
     * 解析原系统 clientid。
     *
     * <p>优先从 JWT payload 解析，因为这是和 token 绑定的真实 clientid；
     * 只有 token 不是 JWT 或解析失败时，才使用 BUSINESS_API_CLIENT_ID 兜底。</p>
     */
    private String resolveClientId(String authToken, String fallbackClientId) {
        String tokenClientId = extractClientIdFromJwt(authToken);
        if (StringUtils.hasText(tokenClientId)) {
            return tokenClientId;
        }
        if (StringUtils.hasText(fallbackClientId)) {
            return fallbackClientId.trim();
        }
        return null;
    }

    /**
     * 将 clientid 同步追加到 query 参数。
     *
     * <p>原系统某些接口/拦截器既看请求头，也可能看请求参数；同时写入可以兼容
     * 这两类校验方式。调用方显式传入 clientid 时保持调用方值不被覆盖。</p>
     */
    private Map<String, Object> withClientIdQueryParam(Map<String, ?> queryParams, String clientId) {
        Map<String, Object> effectiveQueryParams = new LinkedHashMap<>();
        if (!CollectionUtils.isEmpty(queryParams)) {
            queryParams.forEach(effectiveQueryParams::put);
        }
        if (StringUtils.hasText(clientId) && !effectiveQueryParams.containsKey(CLIENT_ID_HEADER)) {
            effectiveQueryParams.put(CLIENT_ID_HEADER, clientId);
        }
        return effectiveQueryParams;
    }

    /**
     * 从 JWT payload 中提取 clientid。
     *
     * <p>这里只做 Base64URL 解码和 JSON 字段读取，不校验 JWT 签名；
     * 签名与登录态有效性仍由原系统负责校验。</p>
     */
    private String extractClientIdFromJwt(String authToken) {
        if (!StringUtils.hasText(authToken)) {
            return null;
        }
        String token = stripBearerPrefix(authToken);
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(addBase64Padding(parts[1]));
            JsonNode payload = OBJECT_MAPPER.readTree(new String(payloadBytes, StandardCharsets.UTF_8));
            for (String key : CLIENT_ID_PAYLOAD_KEYS) {
                JsonNode clientId = payload.get(key);
                if (clientId != null && StringUtils.hasText(clientId.asText())) {
                    return clientId.asText().trim();
                }
            }
            return null;
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }

    /**
     * 清理环境变量中可能多出来的引号和空格。
     *
     * <p>PowerShell 手工设置 token 时，有时会把整段 token 带上引号；
     * 这里统一去掉，避免 Authorization 头里出现额外字符。</p>
     */
    private String normalizeToken(String authToken) {
        if (authToken == null) {
            return null;
        }
        String token = authToken.trim();
        if ((token.startsWith("\"") && token.endsWith("\"")) || (token.startsWith("'") && token.endsWith("'"))) {
            token = token.substring(1, token.length() - 1).trim();
        }
        return token;
    }

    /**
     * 生成 Authorization 请求头值。
     *
     * <p>允许 BUSINESS_API_TOKEN 配成纯 token 或 Bearer token，最终都会转成原系统
     * 期望的 Bearer 形式。</p>
     */
    private String authorizationHeaderValue(String authToken) {
        return authToken.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
            ? authToken
            : BEARER_PREFIX + authToken;
    }

    /**
     * 提取 JWT 本体，供 payload 解码使用。
     */
    private String stripBearerPrefix(String authToken) {
        return authToken.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
            ? authToken.substring(BEARER_PREFIX.length()).trim()
            : authToken.trim();
    }

    /**
     * Base64URL 解码要求长度能被 4 整除；JWT 常省略填充符，这里补齐。
     */
    private String addBase64Padding(String value) {
        int remainder = value.length() % 4;
        if (remainder == 0) {
            return value;
        }
        return value + "=".repeat(4 - remainder);
    }
}
