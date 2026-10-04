package com.aquila.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W0.3：统一响应封装与错误码回归（核心错误码值必须与 03 §三 契约一致）。
 */
class ResultErrorCodeTest {

    @Test
    void errorCodes_matchContract() {
        assertEquals(0, ErrorCode.SUCCESS.getCode());
        assertEquals(1004, ErrorCode.RATE_LIMITED.getCode());
        assertEquals(2001, ErrorCode.UNAUTHORIZED.getCode());
        assertEquals(2002, ErrorCode.TOKEN_EXPIRED.getCode());
        assertEquals(3007, ErrorCode.DIMENSION_NOT_OPEN.getCode());
        assertEquals(3008, ErrorCode.SCORE_NOT_OPEN.getCode());
        assertEquals(4001, ErrorCode.SOURCE_CIRCUIT_OPEN.getCode());
        assertEquals(4004, ErrorCode.REPLAY_WINDOW_EXCEEDED.getCode());
        assertEquals(5001, ErrorCode.DB_ERROR.getCode());
    }

    @Test
    void ok_carriesDataAndCode() {
        Result<String> r = Result.ok("hi");
        assertEquals(0, r.getCode());
        assertEquals("hi", r.getData());
    }

    @Test
    void fail_carriesCodeAndMessage() {
        Result<Void> r = Result.fail(ErrorCode.GROUP_LIMIT);
        assertEquals(3001, r.getCode());
        assertEquals("分组超限(≤20)", r.getMessage());
        assertNull(r.getData());
    }

    @Test
    void bizException_propagatesCode() {
        BizException e = BizException.of(ErrorCode.SCOPE_INVALID);
        assertEquals(3010, e.getCode());
    }
}
