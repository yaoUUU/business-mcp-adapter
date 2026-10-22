package com.earchives.mcp.adapter.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSON-RPC error 对象。
 *
 * <p>code/message 是协议标准字段；data 用来承载 errorCode、suggestion、requestId
 * 等 adapter 侧诊断信息。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcError(int code, String message, Object data) {
}
