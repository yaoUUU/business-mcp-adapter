package com.earchives.mcp.adapter.audit;

import com.earchives.mcp.adapter.auth.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * MCP 请求审计日志服务。
 *
 * <p>这里只记录调用环境、token hash、MCP 方法、工具名、是否成功、错误码和耗时。
 * 不记录手机号、身份证号、短信验证码、原系统 token 等敏感明文。</p>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger("mcp-audit");

    /**
     * 记录一次 MCP 请求的最小审计信息。
     *
     * <p>无论请求成功、鉴权失败还是工具异常，McpController 的 finally 块都会调用这里，
     * 方便排查“OpenClaw 说失败但不知道失败在哪一步”的问题。</p>
     */
    public void record(AuthContext authContext, String requestId, String method, String toolName, boolean ok,
                       String errorCode, long costMillis) {
        String environment = authContext == null ? "-" : authContext.environment();
        String tokenHash = authContext == null ? "-" : authContext.tokenHash();
        log.info("requestId={} environment={} tokenHash={} method={} tool={} ok={} errorCode={} costMs={}",
            requestId, environment, tokenHash, method, toolName, ok, errorCode, costMillis);
    }
}
