package com.earchives.mcp.adapter.tool;

import com.earchives.mcp.adapter.auth.RequestContext;
import com.earchives.mcp.adapter.config.McpAdapterProperties;
import com.earchives.mcp.adapter.mcp.ApiResult;
import com.earchives.mcp.adapter.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CaPsnSilentAuthStatusToolTest {
    private final BusinessApiClient api = mock(BusinessApiClient.class);
    private final DownstreamAuthService auth = mock(DownstreamAuthService.class);
    private final McpAdapterProperties properties = new McpAdapterProperties();
    private final RequestContext context = new RequestContext("status-request", null, null);
    private final DownstreamSession session = new DownstreamSession("secret", "client", 2099676842703380481L,
        "胡剑兰", "胡剑兰", "15178643387", "000000", Long.MAX_VALUE);
    private final CaPsnSilentAuthStatusTool tool = new CaPsnSilentAuthStatusTool(api, properties,
        new McpResponseFactory(new ObjectMapper()), auth);

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(boolean records) {
        when(auth.current(context)).thenReturn(session);
        when(auth.publicSessionInfo(session)).thenReturn(Map.of("mobile", "15178643387"));
        return (Map<String, Object>) ((ApiResult<?>) tool.execute(Map.of("includeRecords", records), context)
            .structuredContent()).data();
    }

    private void reply(String path, Object data) {
        when(api.request(eq("GET"), eq(path), eq(Map.of("userId", session.userId())),
            isNull(), eq(Map.of()), same(session))).thenReturn(Map.of("code", 200, "data", data));
    }

    private void fail(String path) {
        when(api.request(eq("GET"), eq(path), eq(Map.of("userId", session.userId())),
            isNull(), eq(Map.of()), same(session))).thenThrow(new IllegalStateException("downstream failed"));
    }

    @Test void activeAuthorizationOverridesBrokenAccountStatusAndLabelsHistory() {
        var p = properties.getTools().getPsnSilentAuth();
        reply(p.getPsnAccountStatusPath(), Map.of("status", 2, "statusDesc", "已申请未授权"));
        reply(p.getPsnAuthedPath(), true);
        reply(p.getPsnAuthLatestPath(), Map.of("status", 2, "contact", "15178643387",
            "authEndDate", "2026-10-23 00:00:00"));
        reply(p.getPsnAuthListPath(), List.of(Map.of("status", 1), Map.of("status", 2),
            Map.of("status", 3), Map.of("status", 4)));
        var result = execute(true);
        assertEquals(true, result.get("authorizationValid"));
        assertEquals(true, result.get("statusConflict"));
        assertEquals(3, ((Map<?, ?>) result.get("accountStatus")).get("status"));
        assertEquals(2, ((Map<?, ?>) result.get("upstreamAccountStatus")).get("status"));
        assertEquals("授权成功", ((Map<?, ?>) result.get("latestAuth")).get("statusDesc"));
        assertEquals("151****3387", ((Map<?, ?>) result.get("latestAuth")).get("contact"));
        assertEquals("2026-10-23 00:00:00", result.get("expiresAt"));
        List<?> records = (List<?>) result.get("records");
        assertEquals(List.of("进行中", "授权成功", "授权失败", "取消授权"),
            records.stream().map(r -> ((Map<?, ?>) r).get("statusDesc")).toList());
    }

    @Test void expiredAuthorizationKeepsSuccessfulFlowButReportsInvalidNow() {
        var result = SilentAuthStatusSummary.summarize(Map.of("status", 3), false,
            Map.of("status", "2", "authEndDate", "2026-10-23 00:00:00"));
        assertEquals(false, result.get("authorizationValid"));
        assertEquals(2, ((Map<?, ?>) result.get("accountStatus")).get("status"));
        assertEquals("授权成功", ((Map<?, ?>) result.get("latestAuth")).get("statusDesc"));
        assertEquals(true, result.get("statusConflict"));
    }

    @Test void failedValidityQueryIsUnknownEvenWithSuccessfulHistory() {
        var p = properties.getTools().getPsnSilentAuth();
        reply(p.getPsnAccountStatusPath(), Map.of("status", 3));
        fail(p.getPsnAuthedPath());
        reply(p.getPsnAuthLatestPath(), Map.of("status", 2));
        var result = execute(false);
        assertNull(result.get("authorizationValid"));
        assertNull(result.get("authed"));
        assertNull(((Map<?, ?>) result.get("accountStatus")).get("status"));
        assertTrue(result.get("authorizationStatusText").toString().contains("未知"));
    }

    @Test void optionalQueryFailuresDoNotHideConfirmedValidAuthorization() {
        var p = properties.getTools().getPsnSilentAuth();
        fail(p.getPsnAccountStatusPath());
        reply(p.getPsnAuthedPath(), true);
        fail(p.getPsnAuthLatestPath());
        var result = execute(false);
        assertEquals(true, result.get("authorizationValid"));
        assertEquals(3, ((Map<?, ?>) result.get("accountStatus")).get("status"));
        assertEquals(false, ((Map<?, ?>) result.get("latestAuth")).get("available"));
        assertFalse(result.containsKey("expiresAt"));
    }

    @Test void malformedBooleanDoesNotBecomeAuthorized() {
        var result = SilentAuthStatusSummary.summarize(Map.of("status", 2), "true", Map.of());
        assertNull(result.get("authorizationValid"));
    }

    @Test void absentAccountRemainsAbsentButUnknownAccountIsNotInvented() {
        var absent = SilentAuthStatusSummary.summarize(Map.of("status", 1), false, Map.of());
        assertEquals(1, ((Map<?, ?>) absent.get("accountStatus")).get("status"));
        var unknown = SilentAuthStatusSummary.summarize(Map.of("available", false), false, Map.of());
        assertNull(((Map<?, ?>) unknown.get("accountStatus")).get("status"));
    }

    @Test void unauthenticatedCallsCannotReadOtherUsers() {
        assertThrows(RuntimeException.class, () -> tool.execute(Map.of(), context));
        verifyNoInteractions(api);
    }
}
