package com.aquila.sourcepolicy;

/**
 * Licensed-source capabilities checked by {@link SourcePolicy}. Each operation that
 * touches a source must re-check the relevant permission at the point of use (write,
 * display, export) rather than only at scheduling time.
 */
public enum Permission {
    ACCESS,
    STORE_RAW,
    STORE_NORMALIZED,
    ANALYZE,
    DISPLAY,
    EXPORT
}
