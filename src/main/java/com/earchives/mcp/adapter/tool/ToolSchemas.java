package com.earchives.mcp.adapter.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具入参 JSON Schema 的小工具类。
 *
 * <p>这里不用引入额外 schema 库，直接组装 OpenClaw 能识别的简单 Map 结构。
 * 当前三个工具的参数都比较少，保持轻量会更容易排查。</p>
 */
public final class ToolSchemas {

    private ToolSchemas() {
    }

    /**
     * 创建 object 类型 schema。
     *
     * @param properties 字段定义
     * @param required   必填字段名列表
     */
    public static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        // 拒绝模型生成的未声明字段，特别是 userId、flowId 等越权参数。
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 创建 string 类型字段 schema。
     */
    public static Map<String, Object> string(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    /** 创建带长度和正则约束的字符串字段，减少 Agent 生成模糊参数。 */
    public static Map<String, Object> string(String description, Integer minLength, Integer maxLength, String pattern) {
        Map<String, Object> schema = string(description);
        if (minLength != null) schema.put("minLength", minLength);
        if (maxLength != null) schema.put("maxLength", maxLength);
        if (pattern != null) schema.put("pattern", pattern);
        return schema;
    }

    /** 所有工具共享的结构化输出约束。 */
    public static Map<String, Object> result() {
        return Map.of("type", "object", "required", List.of("ok"), "properties", Map.of(
            "ok", Map.of("type", "boolean"), "data", Map.of("type", "object"),
            "errorCode", Map.of("type", "string"), "message", Map.of("type", "string")));
    }}
