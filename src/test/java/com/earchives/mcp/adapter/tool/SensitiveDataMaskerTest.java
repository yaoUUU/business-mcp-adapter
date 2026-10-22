package com.earchives.mcp.adapter.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SensitiveDataMaskerTest {

    @Test
    @SuppressWarnings("unchecked")
    void removesInternalIdentifiersAndMasksContact() {
        Map<String, Object> latestAuth = new LinkedHashMap<>();
        latestAuth.put("caLocalAuthId", "10001");
        latestAuth.put("personId", "PERSON-1");
        latestAuth.put("authId", "20002");
        latestAuth.put("flow_id", "30003");
        latestAuth.put("creatorName", "Test User");
        latestAuth.put("contact", "13700007716");
        latestAuth.put("status", 2);
        latestAuth.put("challengeId", "challenge-1");
        latestAuth.put("userId", 42L);

        Map<String, Object> sanitized = (Map<String, Object>) SensitiveDataMasker.sanitize(latestAuth);

        assertFalse(sanitized.containsKey("caLocalAuthId"));
        assertFalse(sanitized.containsKey("personId"));
        assertFalse(sanitized.containsKey("authId"));
        assertFalse(sanitized.containsKey("flow_id"));
        assertFalse(sanitized.containsKey("creatorName"));
        assertEquals("137****7716", sanitized.get("contact"));
        assertEquals(2, sanitized.get("status"));
        assertEquals("challenge-1", sanitized.get("challengeId"));
        assertEquals(42L, sanitized.get("userId"));
    }
}
