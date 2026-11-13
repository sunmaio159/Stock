package com.aquila.masterdata;

import com.aquila.common.AdminApi;
import com.aquila.common.Page;
import com.aquila.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class SecurityController {
    private final SecurityService securities;
    private final AdminApi api;

    public SecurityController(SecurityService securities, AdminApi api) {
        this.securities = securities;
        this.api = api;
    }

    @GetMapping("/securities")
    public Result<Page<SecurityDto>> search(
            @RequestParam(name = "keyword", defaultValue = "") String keyword,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        return api.respond(request, () -> securities.search(keyword, page, size));
    }
}
