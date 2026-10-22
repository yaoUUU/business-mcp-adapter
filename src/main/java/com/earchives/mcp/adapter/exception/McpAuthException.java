package com.earchives.mcp.adapter.exception;

/**
 * MCP 入口鉴权异常。
 *
 * <p>这个异常只表示 OpenClaw 调用 adapter 的 BUSINESS_MCP_TOKEN 校验失败，
 * 不表示 adapter 调用原系统的 BUSINESS_API_TOKEN 失败。两种 token 的错误
 * 会走不同异常类型，方便定位问题。</p>
 */
public class McpAuthException extends RuntimeException {

    // errorCode 用于 JSON-RPC error.data，suggestion 用于给 OpenClaw/用户展示修复建议。
    private final String errorCode;
    private final String suggestion;

    /**
     * 创建入口鉴权异常。
     */
    public McpAuthException(String errorCode, String message, String suggestion) {
        super(message);
        this.errorCode = errorCode;
        this.suggestion = suggestion;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getSuggestion() {
        return suggestion;
    }
}
