package com.earchives.mcp.adapter.tool;

import com.earchives.mcp.adapter.exception.McpToolException;

import java.util.Map;

/**
 * 原业务系统响应解包工具。
 *
 * <p>原系统有些接口直接返回 data，有些接口返回 R 结构：{code,msg,data}。
 * MCP 工具不应该关心这两种差异，所以统一通过这里取得业务数据，并把非 200
 * 业务响应转换成 MCP 可展示的工具错误。</p>
 */
public final class BusinessResponseUtils {

    private BusinessResponseUtils() {
    }

    /**
     * 兼容原系统常见的 R<T> 响应结构。
     *
     * <p>个人静默签章授权接口有的直接返回业务对象，有的返回
     * {code,msg,data}。工具代码统一先调用 unwrap，后续只处理真正的 data。
     * 如果原系统返回业务错误，这里会转换成 MCP 工具错误，让 OpenClaw 能看到
     * 明确的失败原因和修复建议。</p>
     */
    public static Object unwrap(Object response) {
        if (response instanceof Map<?, ?> map && map.containsKey("code") && map.containsKey("data")) {
            Object code = map.get("code");
            if (isSuccessCode(code)) {
                return map.get("data");
            }
            Object msg = map.get("msg");
            String message = msg == null ? "原业务系统处理失败" : String.valueOf(msg);
            throw new McpToolException("DOWNSTREAM_BUSINESS_ERROR",
                message,
                suggestionFor(code, message), false);
        }
        return response;
    }

    /**
     * 根据原系统错误码/错误文案生成面向测试人员的修复建议。
     *
     * <p>401 在这个模块里最常见的原因是 BUSINESS_API_TOKEN 过期，或 token 中的
     * clientid 与请求里的 clientid 不一致。</p>
     */
    private static String suggestionFor(Object code, String message) {
        String codeText = String.valueOf(code);
        if ("401".equals(codeText) || message.contains("认证失败") || message.contains("无法访问系统资源")) {
            return "原系统已拒绝 BUSINESS_API_TOKEN。请重新登录原系统获取最新 token，重启 adapter，并确认 BUSINESS_API_BASE_URL 指向当前运行的原系统。";
        }
        return "请检查个人 CA 账号、静默签章授权状态、短信验证码和原系统 CA 配置。";
    }

    /**
     * 判断原系统 R 响应是否成功。
     *
     * <p>兼容数字 200 和字符串 "200"，避免 Jackson 反序列化成不同数字类型时误判。</p>
     */
    private static boolean isSuccessCode(Object code) {
        if (code instanceof Number number) {
            return number.intValue() == 200;
        }
        return "200".equals(String.valueOf(code));
    }
}
