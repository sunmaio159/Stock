package com.aquila.common;

import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class AdminContext {
    private final ThreadLocal<String> current = new ThreadLocal<>();

    public <T> T as(String userId, Supplier<T> action) {
        String previous = current.get();
        current.set(userId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        }
    }

    public String requireUserId() {
        String userId = current.get();
        if (userId == null || userId.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Administrator context is missing");
        }
        return userId;
    }
}
