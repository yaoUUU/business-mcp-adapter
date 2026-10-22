package com.earchives.mcp.adapter.exception;

/**
 * MCP 工具层异常。
 *
 * <p>当工具参数不合法、权限不足、限流、原系统业务错误等情况发生时，
 * 工具代码会抛出这个异常。Controller 会把它转换成 {@code isError=true}
 * 的工具调用结果，而不是直接返回 HTTP 500，方便 OpenClaw 在微信里展示错误原因。</p>
 */
public class McpToolException extends RuntimeException {

    /*
     * errorCode 给机器读取，suggestion 给用户/测试人员排查，retryable 用来表达
     * 是否适合稍后重试，例如网络不可用通常可重试，参数错误通常不可重试。
     */
    private final String errorCode;
    private final String suggestion;
    private final boolean retryable;

    /**
     * 创建工具异常。
     *
     * @param errorCode  业务错误码
     * @param message    错误说明
     * @param suggestion 修复建议
     * @param retryable  是否建议重试
     */
    public McpToolException(String errorCode, String message, String suggestion, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.suggestion = suggestion;
        this.retryable = retryable;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
