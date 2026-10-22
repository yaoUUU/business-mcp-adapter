package com.earchives.mcp.adapter.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 统一的工具结构化返回体。
 *
 * <p>该对象会放进 MCP {@code structuredContent}，同时 McpResponseFactory 会把它
 * 序列化进文本 Data JSON。这样 OpenClaw 的不同展示通道都能拿到同一份结果。</p>
 *
 * @param ok         是否成功
 * @param data       成功时的业务数据
 * @param errorCode  失败时的错误码
 * @param message    失败时的错误说明
 * @param suggestion 失败时的排查/修复建议
 * @param retryable  失败时是否建议重试
 * @param meta       requestId、toolName 等追踪信息
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResult<T>(
    boolean ok,
    T data,
    String errorCode,
    String message,
    String suggestion,
    Boolean retryable,
    Map<String, Object> meta
) {

    /**
     * 创建简单成功结果；当前主要保留给扩展场景使用。
     */
    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(true, data, null, null, null, null, null);
    }

    /**
     * 创建只有错误码和错误说明的失败结果。
     */
    public static <T> ApiResult<T> fail(String errorCode, String message) {
        return new ApiResult<>(false, null, errorCode, message, null, false, null);
    }

    /**
     * 创建带修复建议的失败结果。
     */
    public static <T> ApiResult<T> fail(String errorCode, String message, String suggestion) {
        return new ApiResult<>(false, null, errorCode, message, suggestion, false, null);
    }

    /**
     * 创建完整失败结果。
     */
    public static <T> ApiResult<T> fail(String errorCode, String message, String suggestion,
                                        boolean retryable, Map<String, Object> meta) {
        return new ApiResult<>(false, null, errorCode, message, suggestion, retryable, meta);
    }
}
