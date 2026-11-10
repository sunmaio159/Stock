package com.aquila.auth;

import org.springframework.http.HttpStatus;

public final class RateLimitedException extends AuthException {
    public RateLimitedException() {
        super(HttpStatus.TOO_MANY_REQUESTS, 429, "Too many failed authentication attempts");
    }
}
