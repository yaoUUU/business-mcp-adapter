package com.earchives.mcp.adapter.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 本地 requestId 生成器。
 *
 * <p>当调用方没有传 X-Request-Id 时，adapter 会生成一个短 ID 写入工具结果和审计日志。
 * 格式里使用 UTC 时间，避免部署机器时区变化影响日志排序。</p>
 */
public final class RequestIds {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private RequestIds() {
    }

    /**
     * 生成形如 req-20260602-092233-1234 的追踪 ID。
     */
    public static String next() {
        int suffix = ThreadLocalRandom.current().nextInt(1000, 10000);
        return "req-" + OffsetDateTime.now(ZoneOffset.UTC).format(FORMATTER) + "-" + suffix;
    }
}
