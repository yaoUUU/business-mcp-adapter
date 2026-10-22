package com.earchives.mcp.adapter.tool;

import java.util.*;

/** 以 authed 接口判断当前静默授权有效性，区分账户状态与授权流程状态。 */
final class SilentAuthStatusSummary {
    private SilentAuthStatusSummary() { }

    static Map<String, Object> schema() {
        var result = new LinkedHashMap<>(ToolSchemas.result());
        var fields = new LinkedHashMap<String, Object>();
        fields.put("authorizationValid", Map.of("type", List.of("boolean", "null"), "description",
            "当前静默签章授权是否有效，true有效、false无有效授权、null未知；不是身份认证状态。"));
        fields.put("authorizationStatusText", Map.of("type", "string", "description", "最终授权结论，展示时优先使用。"));
        fields.put("latestAuth", Map.of("type", List.of("object", "null"), "description",
            "最近成功授权；status=2表示授权成功，不代表当前仍有效。"));
        fields.put("upstreamAccountStatus", Map.of("type", List.of("object", "null"), "description",
            "上游原始账户状态，仅供诊断，不得覆盖authorizationValid的结论。"));
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("ok", Map.of("type", "boolean"));
        envelope.put("data", Map.of("type", "object", "properties", fields));
        result.put("properties", envelope);
        return result;
    }

    static Map<String, Object> summarize(Object account, Object authed, Object latest) {
        Boolean valid = authed instanceof Boolean value ? value : null;
        String text = valid == null ? "静默签章授权状态未知，请稍后重查"
            : valid ? "静默签章授权成功，当前有效" : "当前无有效静默签章授权";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("authorizationValid", valid);
        out.put("authorizationStatusText", text);
        out.put("authed", valid);
        out.put("authedMeaning", "当前静默签章授权是否有效，不是身份认证状态；null表示未知");
        out.put("upstreamAccountStatus", account);
        String rawStatus = account instanceof Map<?, ?> m ? String.valueOf(m.get("status")) : "";
        boolean conflict = valid != null && ((valid && (rawStatus.equals("1") || rawStatus.equals("2")))
            || (!valid && rawStatus.equals("3")));
        out.put("statusConflict", conflict);
        if (conflict) out.put("statusConflictReason", "上游账户状态与静默授权有效性不一致，最终结论以authorizationValid为准");
        Map<String, Object> normalized = new LinkedHashMap<>();
        Integer status = null;
        if (valid != null) {
            boolean hasHistory = latest instanceof Map<?, ?> m && m.get("status") != null;
            if (valid) status = 3;
            else if (hasHistory || rawStatus.equals("2") || rawStatus.equals("3")) status = 2;
            else if (rawStatus.equals("1")) status = 1;
        }
        normalized.put("status", status);
        normalized.put("statusDesc", valid == null ? text : valid ? "已申请已授权"
            : status == null ? "账户申请状态未知，当前无有效授权"
            : status == 1 ? "未申请未授权" : "已申请，当前无有效授权");
        out.put("accountStatus", normalized);
        out.put("latestAuth", describeRecord(latest));
        if (latest instanceof Map<?, ?> m && m.get("authEndDate") != null) out.put("expiresAt", m.get("authEndDate"));
        return out;
    }

    static Object describeRecords(Object records) {
        return records instanceof List<?> list ? list.stream().map(SilentAuthStatusSummary::describeRecord).toList() : records;
    }

    private static Object describeRecord(Object record) {
        if (!(record instanceof Map<?, ?> source)) return record;
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), value));
        if (source.get("status") != null) {
            String description = switch (String.valueOf(source.get("status"))) {
                case "1" -> "进行中";
                case "2" -> "授权成功";
                case "3" -> "授权失败";
                case "4" -> "取消授权";
                default -> "未知授权流程状态";
            };
            copy.put("statusDesc", description);
            copy.put("statusMeaning", "授权流程状态；成功不代表当前仍在有效期内，当前有效性以authorizationValid为准");
        }
        return copy;
    }
}
