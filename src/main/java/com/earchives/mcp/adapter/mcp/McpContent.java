package com.earchives.mcp.adapter.mcp;

/**
 * MCP content 文本块。
 *
 * <p>工具结果的自然语言/Markdown 文本都通过该对象返回。当前模块只需要 text 类型，
 * 不返回图片、资源或其他内容类型。</p>
 */
public record McpContent(String type, String text) {

    /**
     * 创建 text 类型内容块。
     */
    public static McpContent text(String text) {
        return new McpContent("text", text);
    }
}
