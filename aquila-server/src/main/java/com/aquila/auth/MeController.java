package com.aquila.auth;

import com.aquila.auth.SessionService.SessionPrincipal;
import com.aquila.common.Result;
import com.aquila.common.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class MeController {
    private final SessionService sessions;

    public MeController(SessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/me")
    public Result<MeResponse> me(HttpServletRequest request, HttpServletResponse response) {
        SessionPrincipal principal = sessions.requireAdminSession(
                CsrfFilter.cookieValue(request, CsrfFilter.SESSION_COOKIE));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return Result.success(
                new MeResponse(principal.username(), principal.userId()),
                traceId == null ? null : traceId.toString());
    }

    public record MeResponse(String username, String userId) {}
}
