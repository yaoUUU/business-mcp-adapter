package com.earchives.mcp.adapter.tool;

import com.earchives.mcp.adapter.auth.RequestContext;
import com.earchives.mcp.adapter.mcp.ToolCallResult;
import com.earchives.mcp.adapter.mcp.ToolDefinition;

import java.util.Map;

/**
 * MCP 工具统一接口。
 *
 * <p>每一个被 OpenClaw 看见的工具都实现这个接口。Controller 不关心具体业务，
 * 只通过 name/definition/execute 完成工具发现和调用分发。</p>
 */
public interface McpTool {

    /**
     * MCP 工具名，必须和 application.yml 中授权列表保持一致。
     */
    String name();

    /**
     * 返回 tools/list 中展示的工具元数据和入参 JSON Schema。
     */
    ToolDefinition definition();

    /**
     * 执行工具。
     *
     * @param arguments   OpenClaw 传入的业务参数
     * @param authContext MCP 客户端鉴权上下文
     * @param requestId   本次调用追踪 ID
     */
    ToolCallResult execute(Map<String, Object> arguments, RequestContext context);
}
