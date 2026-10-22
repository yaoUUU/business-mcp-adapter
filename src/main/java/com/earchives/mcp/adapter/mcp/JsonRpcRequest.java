package com.earchives.mcp.adapter.mcp;

import java.util.Map;

/**
 * JSON-RPC 请求模型。
 *
 * <p>当前 Controller 为了兼容动态 arguments 直接接收 Map，这个 record 保留为协议模型，
 * 便于后续如果需要强类型化请求时复用。</p>
 */
public record JsonRpcRequest(String jsonrpc, Object id, String method, Map<String, Object> params) {
}
