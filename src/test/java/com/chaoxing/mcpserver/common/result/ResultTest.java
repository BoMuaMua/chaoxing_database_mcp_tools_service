package com.chaoxing.mcpserver.common.result;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Result} 统一响应包装的单元测试。
 */
class ResultTest {

    @Test
    void okWithData() {
        Map<String, Object> data = Map.of("rowCount", 3);
        Result<Map<String, Object>> r = Result.ok(data);
        assertTrue(r.isSuccess());
        assertEquals(Result.SUCCESS_CODE, r.getCode());
        assertEquals(data, r.getData());
        assertNull(r.getMessage());
    }

    @Test
    void okWithoutData() {
        Result<String> r = Result.ok();
        assertTrue(r.isSuccess());
        assertNull(r.getData());
    }

    @Test
    void failWithCodeAndMessage() {
        Result<Object> r = Result.fail(1004, "unique key conflict");
        assertFalse(r.isSuccess());
        assertEquals(1004, r.getCode());
        assertEquals("unique key conflict", r.getMessage());
        assertNull(r.getData());
    }

    @Test
    void toMapOmitsNulls() {
        Result<String> r = Result.ok("x");
        Map<String, Object> m = r.toMap();
        assertEquals(true, m.get("success"));
        assertEquals(0, m.get("code"));
        assertEquals("x", m.get("data"));
        // message 为 null 时不出现在 map 里
        assertFalse(m.containsKey("message"));
    }

    @Test
    void toMapContainsMessageOnFailure() {
        Result<Object> r = Result.fail(1003, "db timeout");
        Map<String, Object> m = r.toMap();
        assertEquals(false, m.get("success"));
        assertEquals(1003, m.get("code"));
        assertEquals("db timeout", m.get("message"));
        // data 为 null 时不出现
        assertFalse(m.containsKey("data"));
        assertNotNull(m);
    }
}
