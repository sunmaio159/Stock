package com.aquila.common;

import com.aquila.auth.CsrfFilter;
import com.aquila.auth.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class AdminApi {
    private final SessionService sessions;
    private final AdminContext admin;

    public AdminApi(SessionService sessions, AdminContext admin) {
        this.sessions = sessions;
        this.admin = admin;
    }

    public <T> Result<T> respond(HttpServletRequest request, Supplier<T> action) {
        SessionService.SessionPrincipal principal = sessions.requireAdminSession(
                CsrfFilter.cookieValue(request, CsrfFilter.SESSION_COOKIE));
        T data = admin.as(principal.userId(), action);
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return Result.success(data, traceId == null ? null : traceId.toString());
    }
}
