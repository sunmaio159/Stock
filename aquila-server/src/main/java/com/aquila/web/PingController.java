package com.aquila.web;

import com.aquila.common.Result;
import com.aquila.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * W0 骨架探针接口，用于验证「统一 Result + traceId + JWT 过滤链」是否贯通。
 * GET /api/ping 不在白名单内 → 需携带 Authorization: Bearer <AT>；本接口作为受保护示例。
 */
@RestController
@RequestMapping("/api")
public class PingController {

    @GetMapping("/ping")
    public Result<Map<String, Object>> ping(HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("time", OffsetDateTime.now().toString());
        Object cu = request.getAttribute("currentUser");
        if (cu instanceof CurrentUser user) {
            data.put("uid", user.uid());
            data.put("role", user.role());
        }
        return Result.ok(data);
    }
}
