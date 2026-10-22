package com.earchives.mcp.adapter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * business.mcp 前缀下的所有配置项。
 *
 * <p>这个类是 adapter 的配置总入口：MCP 服务信息、下游原系统地址、
 * OpenClaw 访问凭据、限流策略，以及个人静默签章授权相关接口路径都从这里读取。
 * 字段保留普通 JavaBean getter/setter，是为了让 Spring Boot 能稳定绑定
 * application.yml 和环境变量。</p>
 */
@ConfigurationProperties(prefix = "business.mcp")
public class McpAdapterProperties {

    private String serverName = "business-mcp-adapter";
    private String serverVersion = "0.1.0";
    private String protocolVersion = "2025-06-18";
    private String instructions = "This MCP server only exposes personal CA silent-sign authorization tools.";
    private Downstream downstream = new Downstream();
    private RateLimit rateLimit = new RateLimit();
    private Auth auth = new Auth();
    private Tools tools = new Tools();

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public Downstream getDownstream() {
        return downstream;
    }

    public void setDownstream(Downstream downstream) {
        this.downstream = downstream;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public Tools getTools() {
        return tools;
    }

    public void setTools(Tools tools) {
        this.tools = tools;
    }

    /**
     * Adapter 访问原业务系统时使用的连接配置。
     *
     * <p>{@code authToken} 是原系统登录 token，不是 OpenClaw 调用 adapter 的 token。
     * {@code clientId} 只作为兜底配置；正常情况下会在 BusinessApiClient 里从 JWT
     * payload 自动解析真实 clientid。</p>
     */
    public static class Downstream {
        private String baseUrl = "http://localhost:8080";
        private String authToken;
        private String clientId;
        private String loginPath = "/auth/login";
        private String userInfoPath = "/system/user/getInfo";
        private String loginClientId = "e5cd7e4891bf95d1d19206ce24a7b32e";
        private String loginGrantType = "password";
        private String loginTenantId = "000000";
        private Duration loginCacheTtl = Duration.ofMinutes(25);
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(10);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getAuthToken() {
            return authToken;
        }

        public void setAuthToken(String authToken) {
            this.authToken = authToken;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getLoginPath() {
            return loginPath;
        }

        public void setLoginPath(String loginPath) {
            this.loginPath = loginPath;
        }

        public String getUserInfoPath() {
            return userInfoPath;
        }

        public void setUserInfoPath(String userInfoPath) {
            this.userInfoPath = userInfoPath;
        }

        public String getLoginClientId() {
            return loginClientId;
        }

        public void setLoginClientId(String loginClientId) {
            this.loginClientId = loginClientId;
        }

        public String getLoginGrantType() {
            return loginGrantType;
        }

        public void setLoginGrantType(String loginGrantType) {
            this.loginGrantType = loginGrantType;
        }

        public String getLoginTenantId() {
            return loginTenantId;
        }

        public void setLoginTenantId(String loginTenantId) {
            this.loginTenantId = loginTenantId;
        }

        public Duration getLoginCacheTtl() {
            return loginCacheTtl;
        }

        public void setLoginCacheTtl(Duration loginCacheTtl) {
            this.loginCacheTtl = loginCacheTtl;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    /**
     * MCP 调用限流配置。
     *
     * <p>限流按 MCP 客户端 token hash + 工具名划分，防止微信通道或自动化流程
     * 在短时间内重复调用更新授权类接口。</p>
     */
    public static class RateLimit {
        private Duration window = Duration.ofMinutes(1);
        private int maxRequests = 60;

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public int getMaxRequests() {
            return maxRequests;
        }

        public void setMaxRequests(int maxRequests) {
            this.maxRequests = maxRequests;
        }
    }

    /**
     * 允许访问 adapter 的 MCP 客户端列表。
     *
     * <p>这里配置的是 OpenClaw 到 adapter 的 Bearer token 和可用工具范围，
     * 与 adapter 到原系统的 BUSINESS_API_TOKEN 是两套不同凭据。</p>
     */
    public static class Auth {
        private List<Client> clients = new ArrayList<>();

        public List<Client> getClients() {
            return clients;
        }

        public void setClients(List<Client> clients) {
            this.clients = clients;
        }
    }

    /**
     * 单个 MCP 客户端配置。
     *
     * <p>{@code tools} 支持配置具体工具名，也支持使用 * 表示允许全部工具。
     * 当前模块实际只注册个人静默签章授权相关的三个工具。</p>
     */
    public static class Client {
        /** OpenClaw 客户端稳定标识，仅用于主体隔离和审计，不是秘密。 */
        private String clientId = "openclaw-wechat";
        /** MCP 入口 Bearer Token，属于敏感配置，禁止写入日志。 */
        private String token;
        /** 个人微信请求签名密钥，只在 Adapter 内用于 HMAC 校验。 */
        private String channelSigningSecret;
        private String environment = "local";
        private boolean enabled = true;
        private Set<String> tools = new LinkedHashSet<>();

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getChannelSigningSecret() {
            return channelSigningSecret;
        }

        public void setChannelSigningSecret(String channelSigningSecret) {
            this.channelSigningSecret = channelSigningSecret;
        }
        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getEnvironment() {
            return environment;
        }

        public void setEnvironment(String environment) {
            this.environment = environment;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Set<String> getTools() {
            return tools;
        }

        public void setTools(Set<String> tools) {
            this.tools = tools;
        }
    }

    /**
     * 工具相关的业务接口配置集合。
     *
     * <p>这里只保留个人静默签章授权工具需要的配置项，不再保留订单、客户、
     * 工单或通用 PDF 签章配置，避免 OpenClaw 暴露与本功能无关的能力。</p>
     */
    public static class Tools {
        /**
         * 个人静默签章授权相关接口路径。
         *
         * <p>这里不再保留订单、客户、工单、通用 PDF 签章等配置项，避免 OpenClaw
         * 暴露与“个人静默签章授权”无关的工具能力。</p>
         */
        private PsnSilentAuth psnSilentAuth = new PsnSilentAuth();

        public PsnSilentAuth getPsnSilentAuth() {
            return psnSilentAuth;
        }

        public void setPsnSilentAuth(PsnSilentAuth psnSilentAuth) {
            this.psnSilentAuth = psnSilentAuth;
        }
    }

    /**
     * “个人签章授权记录”页面对应的原系统接口集合。
     *
     * <p>OpenClaw 侧只暴露 start/finish/status 三个工具；这些字段只负责把工具
     * 映射到原系统已经存在的接口，不在 adapter 里重新实现 CA/e签宝业务逻辑。</p>
     */
    public static class PsnSilentAuth {
        /*
         * 以下路径对应原系统“个人签章授权记录”页面背后的接口。
         * start/finish/status 三个 MCP 工具只负责组合这些现成接口：
         * 1. telecom3Factors 发送三要素校验短信；
         * 2. smsVerify 校验短信验证码；
         * 3. psnAuth/approve 创建并批准授权；
         * 4. list/latest/authed/status 回读授权状态。
         */
        private String telecom3FactorsPath = "/ca/local/account/psn/telecom3Factors";
        private String telecom3FactorsMethod = "POST";
        private String smsVerifyPath = "/ca/local/account/psn/smsVerify";
        private String smsVerifyMethod = "POST";
        private String psnAuthPath = "/ca/local/auth/psn/auth";
        private String psnAuthMethod = "POST";
        private String psnAuthApprovePath = "/ca/local/auth/psn/auth/approve";
        private String psnAuthApproveMethod = "POST";
        private String psnAuthListPath = "/ca/local/auth/psn/auth";
        private String psnAuthListMethod = "GET";
        private String psnAuthLatestPath = "/ca/local/auth/psn/auth/latest";
        private String psnAuthLatestMethod = "GET";
        private String psnAuthedPath = "/ca/local/auth/psn/authed";
        private String psnAuthedMethod = "GET";
        private String psnAccountStatusPath = "/ca/local/account/psn/status";
        private String psnAccountStatusMethod = "GET";

        public String getTelecom3FactorsPath() {
            return telecom3FactorsPath;
        }

        public void setTelecom3FactorsPath(String telecom3FactorsPath) {
            this.telecom3FactorsPath = telecom3FactorsPath;
        }

        public String getTelecom3FactorsMethod() {
            return telecom3FactorsMethod;
        }

        public void setTelecom3FactorsMethod(String telecom3FactorsMethod) {
            this.telecom3FactorsMethod = telecom3FactorsMethod;
        }

        public String getSmsVerifyPath() {
            return smsVerifyPath;
        }

        public void setSmsVerifyPath(String smsVerifyPath) {
            this.smsVerifyPath = smsVerifyPath;
        }

        public String getSmsVerifyMethod() {
            return smsVerifyMethod;
        }

        public void setSmsVerifyMethod(String smsVerifyMethod) {
            this.smsVerifyMethod = smsVerifyMethod;
        }

        public String getPsnAuthPath() {
            return psnAuthPath;
        }

        public void setPsnAuthPath(String psnAuthPath) {
            this.psnAuthPath = psnAuthPath;
        }

        public String getPsnAuthMethod() {
            return psnAuthMethod;
        }

        public void setPsnAuthMethod(String psnAuthMethod) {
            this.psnAuthMethod = psnAuthMethod;
        }

        public String getPsnAuthApprovePath() {
            return psnAuthApprovePath;
        }

        public void setPsnAuthApprovePath(String psnAuthApprovePath) {
            this.psnAuthApprovePath = psnAuthApprovePath;
        }

        public String getPsnAuthApproveMethod() {
            return psnAuthApproveMethod;
        }

        public void setPsnAuthApproveMethod(String psnAuthApproveMethod) {
            this.psnAuthApproveMethod = psnAuthApproveMethod;
        }

        public String getPsnAuthListPath() {
            return psnAuthListPath;
        }

        public void setPsnAuthListPath(String psnAuthListPath) {
            this.psnAuthListPath = psnAuthListPath;
        }

        public String getPsnAuthListMethod() {
            return psnAuthListMethod;
        }

        public void setPsnAuthListMethod(String psnAuthListMethod) {
            this.psnAuthListMethod = psnAuthListMethod;
        }

        public String getPsnAuthLatestPath() {
            return psnAuthLatestPath;
        }

        public void setPsnAuthLatestPath(String psnAuthLatestPath) {
            this.psnAuthLatestPath = psnAuthLatestPath;
        }

        public String getPsnAuthLatestMethod() {
            return psnAuthLatestMethod;
        }

        public void setPsnAuthLatestMethod(String psnAuthLatestMethod) {
            this.psnAuthLatestMethod = psnAuthLatestMethod;
        }

        public String getPsnAuthedPath() {
            return psnAuthedPath;
        }

        public void setPsnAuthedPath(String psnAuthedPath) {
            this.psnAuthedPath = psnAuthedPath;
        }

        public String getPsnAuthedMethod() {
            return psnAuthedMethod;
        }

        public void setPsnAuthedMethod(String psnAuthedMethod) {
            this.psnAuthedMethod = psnAuthedMethod;
        }

        public String getPsnAccountStatusPath() {
            return psnAccountStatusPath;
        }

        public void setPsnAccountStatusPath(String psnAccountStatusPath) {
            this.psnAccountStatusPath = psnAccountStatusPath;
        }

        public String getPsnAccountStatusMethod() {
            return psnAccountStatusMethod;
        }

        public void setPsnAccountStatusMethod(String psnAccountStatusMethod) {
            this.psnAccountStatusMethod = psnAccountStatusMethod;
        }
    }
}
