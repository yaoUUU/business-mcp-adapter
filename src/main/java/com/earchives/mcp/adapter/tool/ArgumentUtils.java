package com.earchives.mcp.adapter.tool;

import com.earchives.mcp.adapter.exception.McpToolException;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * MCP 工具参数读取与校验工具类。
 *
 * <p>OpenClaw 传入的 arguments 是动态 JSON 对象，进入 Java 后统一是 Map。
 * 这里集中处理必填、布尔值和 Long 转换，避免三个业务工具重复写校验代码。</p>
 */
public final class ArgumentUtils {

    private ArgumentUtils() {
    }

    /**
     * 读取必填字符串参数。
     *
     * <p>OpenClaw/微信通道传入的 JSON 参数都先落到 Map 里，这里统一做空值校验，
     * 并给调用者返回可读的 MCP 工具错误。</p>
     */
    public static String requiredString(Map<String, Object> arguments, String name, String example) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            throw new McpToolException(
                "INVALID_ARGUMENT",
                name + " 不能为空",
                "请让用户提供 " + name + "，例如 " + example + "。",
                false
            );
        }
        return String.valueOf(value).trim();
    }

    /**
     * 读取可选字符串参数。
     */
    public static String optionalString(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    /**
     * 对会改变授权状态的工具做显式确认。
     */
    public static boolean requiredTrue(Map<String, Object> arguments, String name, String message) {
        Object value = arguments == null ? null : arguments.get(name);
        if (Boolean.TRUE.equals(value)) {
            return true;
        }
        if (value instanceof String stringValue && "true".equalsIgnoreCase(stringValue.trim())) {
            return true;
        }
        throw new McpToolException("RISK_CONFIRMATION_REQUIRED", message,
            "请显式传入 " + name + "=true 后再调用该高风险工具。", false);
    }

    /**
     * 读取可选布尔参数。
     *
     * <p>兼容 JSON boolean 和字符串 "true"/"false"，便于微信通道自然语言调用时
     * OpenClaw 把参数转换成不同类型。</p>
     */
    public static boolean booleanValue(Map<String, Object> arguments, String name, boolean defaultValue) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * 读取原系统用户 ID。
     *
     * <p>前端和 OpenClaw 里建议把 userId 当字符串传入，因为原系统用户 ID 超过
     * JavaScript 安全整数范围。这里最终转成 Long，是为了和原系统接口保持一致。</p>
     */
    public static Long requiredLong(Map<String, Object> arguments, String name, String example) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            throw new McpToolException("INVALID_ARGUMENT", name + " 不能为空",
                "请让用户提供 " + name + "，例如 " + example + "。", false);
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new McpToolException("INVALID_ARGUMENT", name + " 必须是长整数",
                "请提供数字 ID，例如 " + example + "。", false);
        }
    }

    /**
     * 读取可选长整数参数。
     */
    public static Long optionalLong(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new McpToolException("INVALID_ARGUMENT", name + " 必须是长整数",
                "请提供数字 ID，或者省略该字段并使用原系统账号密码自动登录。", false);
        }
    }
}
