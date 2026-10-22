package com.earchives.mcp.adapter.service;

import com.earchives.mcp.adapter.mcp.ApiResult;
import com.earchives.mcp.adapter.mcp.ToolCallResult;
import com.earchives.mcp.adapter.tool.SensitiveDataMasker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 工具执行结果包装器。
 *
 * <p>OpenClaw 会读取 structuredContent，同时微信通道有时只展示 content.text。
 * 因此这里把成功和失败的结构化结果都同步写入文本中的 Data JSON，确保用户在微信里
 * 也能看到 authId、授权状态、错误码和修复建议。</p>
 */
@Component
public class McpResponseFactory {

    // 防止微信通道展示超长 JSON 时过度刷屏；结构化结果不受这个文本截断影响。
    private static final int MAX_TEXT_DATA_LENGTH = 6000;

    private final ObjectMapper objectMapper;

    public McpResponseFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 构造工具成功结果。
     *
     * @param toolName  MCP 工具名
     * @param requestId 本次请求的追踪 ID
     * @param data      工具真实返回数据，会放入 structuredContent.data
     */
    public ToolCallResult ok(String toolName, String requestId, Object data) {
        Map<String, Object> meta = meta(toolName, requestId);
        ApiResult<Object> result = new ApiResult<>(true, data, null, null, null, null, meta);
        return ToolCallResult.ok(result, successText(toolName, data));
    }

    /**
     * 构造工具失败结果。
     *
     * <p>这里不抛 JSON-RPC error，而是返回 isError=true 的 tool result。
     * 这样 OpenClaw 能把错误当作一次工具调用结果展示出来，并保留 Data JSON。</p>
     */
    public ToolCallResult fail(String toolName, String requestId, String errorCode, String message,
                               String suggestion, boolean retryable) {
        ApiResult<Object> result = ApiResult.fail(errorCode, message, suggestion, retryable, meta(toolName, requestId));
        String text = "Tool " + toolName + " failed: " + message
            + (suggestion == null ? "" : " " + suggestion)
            + "\nData JSON:\n```json\n" + toJson(result) + "\n```";
        return ToolCallResult.error(result, text);
    }

    /**
     * 所有工具结果都带上相同的 meta，方便从微信消息、adapter 日志和原系统日志里串联排查。
     */
    private Map<String, Object> meta(String toolName, String requestId) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("toolName", toolName);
        meta.put("requestId", requestId);
        return meta;
    }

    /**
     * 成功文本中附带 Data JSON，解决部分 OpenClaw 展示层不显示 structuredContent 的问题。
     */
    private String successText(String toolName, Object data) {
        /*
         * OpenClaw 的微信通道有时只把 content.text 展示给用户，不完整展示
         * structuredContent。这里把同一份结果再序列化成 Data JSON，确保用户能在
         * 微信里看到 authId、授权状态和最新授权记录。
         */
        return "Tool " + toolName + " succeeded.\nData JSON:\n```json\n" + toJson(data) + "\n```";
    }

    /**
     * 将任意数据安全序列化成漂亮 JSON；序列化失败时退化为 String.valueOf。
     */
    private String toJson(Object data) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            if (json.length() <= MAX_TEXT_DATA_LENGTH) {
                return json;
            }
            return json.substring(0, MAX_TEXT_DATA_LENGTH) + "\n... truncated ...";
        } catch (JsonProcessingException e) {
            return String.valueOf(data);
        }
    }
}
