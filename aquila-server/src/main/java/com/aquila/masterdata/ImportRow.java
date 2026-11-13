package com.aquila.masterdata;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ImportRow(int rowNo, String rawText, String normalizedCode, String errorCode) {
    private static final Pattern CANONICAL = Pattern.compile("^(SH|SZ):([0-9]{6})$");
    private static final Pattern BARE_CODE = Pattern.compile("^[0-9]{6}$");
    private static final Pattern MARKET_PREFIXED = Pattern.compile("^([A-Za-z]{2}):(.+)$");

    public static ImportRow parse(int rowNo, String rawText) {
        String original = rawText == null ? "" : rawText;
        String candidate = original.trim().toUpperCase(Locale.ROOT);

        Matcher canonical = CANONICAL.matcher(candidate);
        if (canonical.matches()) {
            return new ImportRow(
                    rowNo, original, canonical.group(1) + ":" + canonical.group(2), null);
        }

        String error = classify(candidate);
        return new ImportRow(rowNo, original, null, error);
    }

    public boolean valid() {
        return errorCode == null;
    }

    private static String classify(String candidate) {
        if (candidate.isEmpty()) {
            return ImportErrorCode.EMPTY_ROW;
        }
        if (BARE_CODE.matcher(candidate).matches()) {
            return ImportErrorCode.MISSING_MARKET;
        }
        Matcher prefixed = MARKET_PREFIXED.matcher(candidate);
        if (prefixed.matches()
                && !"SH".equals(prefixed.group(1))
                && !"SZ".equals(prefixed.group(1))) {
            return ImportErrorCode.INVALID_MARKET;
        }
        return ImportErrorCode.INVALID_CODE;
    }
}
