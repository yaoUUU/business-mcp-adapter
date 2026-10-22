package com.earchives.mcp.adapter.tool;

import com.earchives.mcp.adapter.auth.AuthContext;
import com.earchives.mcp.adapter.exception.McpToolException;
import com.earchives.mcp.adapter.mcp.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MCP 工具注册表。
 *
 * <p>Spring 会把所有实现 {@link McpTool} 的 Bean 注入进来。当前模块只应该有
 * ca_psn_silent_auth_start、ca_psn_silent_auth_finish、ca_psn_silent_auth_status
 * 三个工具；如果 tools/list 里出现别的工具，优先检查是否有旧工具类被重新加入。</p>
 */
@Component
public class ToolRegistry {

    private final Map<String, McpTool> tools;

    /**
     * 将工具列表按名称索引。
     *
     * <p>使用 toUnmodifiableMap 可以防止运行期被误改，工具集合只在 Spring 启动时确定。</p>
     */
    public ToolRegistry(List<McpTool> toolList) {
        this.tools = toolList.stream().collect(Collectors.toUnmodifiableMap(McpTool::name, Function.identity()));
    }

    /**
     * 返回当前 MCP 客户端可见的工具定义。
     */
    public List<ToolDefinition> list(AuthContext context) {
        return tools.values().stream()
            .filter(tool -> context.canUseTool(tool.name()))
            .sorted(Comparator.comparing(McpTool::name))
            .map(McpTool::definition)
            .toList();
    }

    /**
     * 根据工具名取工具，并进行二次权限检查。
     *
     * <p>即使调用方绕过 tools/list 直接 tools/call，也会在这里被拦截。</p>
     */
    public McpTool get(String toolName, AuthContext context) {
        McpTool tool = tools.get(toolName);
        if (tool == null) {
            throw new McpToolException(
                "TOOL_NOT_FOUND",
                "未找到 MCP 工具：" + toolName,
                "请先调用 tools/list 获取当前环境允许使用的工具。",
                false
            );
        }
        if (!context.canUseTool(toolName)) {
            throw new McpToolException(
                "PERMISSION_DENIED",
                "当前 MCP 客户端没有访问该工具的权限：" + toolName,
                "请改用已授权工具，或联系管理员为该 OpenClaw 环境授权。",
                false
            );
        }
        return tool;
    }
}
