package com.earchives.mcp.adapter.exception;

/**
 * 原业务系统调用异常。
 *
 * <p>BusinessApiClient 在请求原系统出现 HTTP 错误或网络错误时抛出该异常。
 * 工具层捕获后会转成 MCP 工具结果，并根据 statusCode 判断是否可重试。</p>
 */
public class DownstreamException extends RuntimeException {

    // statusCode 为 0 表示没有拿到 HTTP 响应，通常是连接失败、超时或下游不可达。
    private final int statusCode;
    // responseBody 保留原系统响应体，便于日志/调试使用；正常工具返回会做脱敏处理。
    private final String responseBody;

    /**
     * 创建原系统调用异常。
     */
    public DownstreamException(String message, int statusCode, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
