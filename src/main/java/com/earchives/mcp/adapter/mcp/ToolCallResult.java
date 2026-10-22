package com.earchives.mcp.adapter.mcp;

import java.util.List;

/**
 * MCP tools/call 的返回模型。
 *
 * <p>structuredContent 存放机器可读 JSON，content 存放展示文本，isError 表示这次
 * 工具调用是否失败。即使 isError=true，外层 JSON-RPC 仍然可以是 result。</p>
 */
public record ToolCallResult(Object structuredContent, List<McpContent> content, boolean isError) {

    /**
     * 创建成功工具结果。
     */
    public static ToolCallResult ok(Object structuredContent, String text) {
        return new ToolCallResult(structuredContent, List.of(McpContent.text(text)), false);
    }

    /**
     * 创建失败工具结果。
     */
    public static ToolCallResult error(Object structuredContent, String text) {
        return new ToolCallResult(structuredContent, List.of(McpContent.text(text)), true);
    }
}
