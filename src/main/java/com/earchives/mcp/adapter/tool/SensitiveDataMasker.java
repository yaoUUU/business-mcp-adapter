package com.earchives.mcp.adapter.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 工具返回结果脱敏器。
 *
 * <p>个人静默签章授权涉及手机号、身份证号、验证码等敏感信息。adapter 需要把结果
 * 通过 OpenClaw/微信展示给用户，所以在返回前对常见敏感字段做递归脱敏。</p>
 */
public final class SensitiveDataMasker {

    private static final Pattern PHONE = Pattern.compile("(\\d{3})\\d{4}(\\d{4})");

    private SensitiveDataMasker() {
    }

    /**
     * 递归脱敏 Map/List 中的敏感字段。
     *
     * <p>字段名匹配手机号、邮箱、身份证、地址等关键词时脱敏；其他字段继续递归处理，
     * 以兼容原系统返回的嵌套 data 结构。</p>
     */
    @SuppressWarnings("unchecked")
    public static Object sanitize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (shouldOmit(key)) {
                    continue;
                }
                sanitized.put(key, sanitizeValue(key, entry.getValue()));
            }
            return sanitized;
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>(list.size());
            for (Object item : list) {
                sanitized.add(sanitize(item));
            }
            return sanitized;
        }
        return value;
    }

    /**
     * 按字段名判断具体脱敏策略。
     */
    private static Object sanitizeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        if (normalized.contains("contact")) {
            return maskPhone(String.valueOf(value));
        }
        if (normalized.contains("phone") || normalized.contains("mobile") || normalized.contains("tel") || normalized.contains("手机号")) {
            return maskPhone(String.valueOf(value));
        }
        if (normalized.contains("password") || normalized.contains("token") || normalized.contains("authcode")
            || normalized.contains("密码") || normalized.contains("验证码")) {
            return "[已脱敏]";
        }
        if (normalized.contains("email") || normalized.contains("邮箱")) {
            return maskEmail(String.valueOf(value));
        }
        if (normalized.contains("idcard") || normalized.contains("identity") || normalized.contains("address")
            || normalized.contains("身份证") || normalized.contains("地址")) {

            return "[已脱敏]";
        }
        return sanitize(value);
    }

    /**
     * Internal identifiers are workflow implementation details and must not be
     * returned to MCP clients.
     */
    private static boolean shouldOmit(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.equals("flowid")
            || normalized.equals("authid")
            || normalized.equals("personid")
            || normalized.equals("calocalauthid")
            || normalized.equals("creatorname");
    }

    /**
     * 手机号脱敏：保留前三位和后四位。
     */
    public static String maskPhone(String value) {
        if (value == null) {
            return null;
        }
        return PHONE.matcher(value).replaceAll("$1****$2");
    }

    /**
     * 邮箱脱敏：保留首字符和域名。
     */
    public static String maskEmail(String value) {
        if (value == null) {
            return null;
        }
        int at = value.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return value.charAt(0) + "***" + value.substring(at);
    }
}
