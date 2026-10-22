package com.earchives.mcp.adapter.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * MCP tools/list 中单个工具的定义。
 *
 * <p>OpenClaw 根据这里的 name、description、inputSchema 来决定如何向用户解释工具、
 * 如何把自然语言参数转换为 JSON arguments。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition(
    String name,
    String title,
    String description,
    Map<String, Object> inputSchema,
    Map<String, Object> outputSchema,
    ToolAnnotations annotations,
    Map<String, Object> metadata
) {
}
