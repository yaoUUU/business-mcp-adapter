package com.earchives.mcp.adapter.mcp;

/**
 * MCP 工具注解信息。
 *
 * <p>这些 hint 会出现在 tools/list 中，帮助 OpenClaw 判断工具是否只读、
 * 是否幂等、是否可能影响外部世界。个人授权完成工具会改变原系统授权状态，
 * 所以不会标记为 readOnly/idempotent。</p>
 */
public record ToolAnnotations(
    boolean readOnlyHint,
    boolean destructiveHint,
    boolean idempotentHint,
    boolean openWorldHint
) {
}
