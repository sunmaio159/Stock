package com.aquila.masterdata;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical market-qualified security identifiers for the V1 API.
 *
 * <p>A bare six-digit code is never accepted by {@link #parse(String)}: the
 * market must be supplied explicitly through {@link #resolve(String, String)}
 * and validated against master data, rather than guessed from a code prefix.
 */
public final class SecurityCode {
    private static final Pattern CANONICAL = Pattern.compile("^(SH|SZ):([0-9]{6})$");
    private static final Pattern BARE_CODE = Pattern.compile("^[0-9]{6}$");

    private SecurityCode() {
    }

    public static Parsed parse(String canonical) {
        if (canonical == null) {
            throw new IllegalArgumentException("security code is required");
        }
        Matcher matcher = CANONICAL.matcher(canonical);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("security code must be SH:000000 or SZ:000000");
        }
        return new Parsed(matcher.group(1), matcher.group(2), matcher.group(1) + ":" + matcher.group(2));
    }

    public static Parsed resolve(String market, String bareCode) {
        String normalizedMarket = market == null ? null : market.trim().toUpperCase(Locale.ROOT);
        if (!"SH".equals(normalizedMarket) && !"SZ".equals(normalizedMarket)) {
            throw new IllegalArgumentException("market must be SH or SZ");
        }
        if (bareCode == null || !BARE_CODE.matcher(bareCode.trim()).matches()) {
            throw new IllegalArgumentException("bare code must be exactly six digits");
        }
        return parse(normalizedMarket + ":" + bareCode.trim());
    }

    public static String normalizeAlias(String alias) {
        if (alias == null) {
            throw new IllegalArgumentException("alias is required");
        }
        String normalized = alias.replace('\u3000', ' ')
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        return normalized;
    }

    public record Parsed(String exchange, String code, String canonical) {
    }
}
