package ru.reinform.rinrif.managertools.core;

import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.UUID;

final class CoreUtils {
    private CoreUtils() {
    }

    static String nowIso() {
        return DateTimeFormatter.ISO_INSTANT.format(new Date().toInstant());
    }

    static String createIdentifier(String prefix) {
        return prefix + "_" + Long.toString(System.currentTimeMillis(), 36) + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    static String safe(String value) {
        return value == null ? "" : value;
    }

    static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
