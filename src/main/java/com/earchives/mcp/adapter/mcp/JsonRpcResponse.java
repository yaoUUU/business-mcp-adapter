package com.earchives.mcp.adapter.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSON-RPC 2.0 响应模型。
 *
 * <p>MCP HTTP 入口始终返回 JSON-RPC 响应；工具执行失败时通常放在 result.isError，
 * 只有协议错误、方法不存在、入口鉴权失败等才使用 error 字段。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcResponse(String jsonrpc, Object id, Object result, JsonRpcError error) {

    /**
     * 创建 JSON-RPC 成功响应。
     */
    public static JsonRpcResponse result(Object id, Object result) {
        return new JsonRpcResponse("2.0", id, result, null);
    }

    /**
     * 创建 JSON-RPC 错误响应。
     */
    public static JsonRpcResponse error(Object id, int code, String message, Object data) {
        return new JsonRpcResponse("2.0", id, null, new JsonRpcError(code, message, data));
    }
}
