package ru.reinform.rinrif.managertools.atr2spec;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Atr2SpecTextUtils {
    private static final Pattern JIRA_KEY = Pattern.compile("\\b([A-Z][A-Z0-9]+-\\d+)\\b");
    private static final Pattern MGSN_KEY = Pattern.compile("\\b(MGSN-\\s*\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>)]*");
    private static final Pattern XPATH = Pattern.compile("\\b[A-Za-z][A-Za-z0-9_-]*(?:/[A-Za-z0-9:_-]+){1,}\\b");

    private Atr2SpecTextUtils() {
    }

    static String htmlToText(String value) {
        String text = value == null ? "" : value;
        text = text.replaceAll("(?i)<\\s*(br|p|tr|li|h[1-6])[^>]*>", "\n");
        text = text.replaceAll("(?i)</\\s*(p|tr|li|h[1-6])\\s*>", "\n");
        text = text.replaceAll("(?i)<\\s*(td|th)[^>]*>", " | ");
        text = text.replaceAll("(?i)</\\s*(td|th)\\s*>", " ");
        text = text.replaceAll("<[^>]+>", " ");
        text = unescape(text).replace('\u00A0', ' ');
        text = text.replaceAll("[ \\t\\r\\f\\x0B]+", " ");
        text = text.replaceAll(" *\\| *", " | ");
        StringBuilder result = new StringBuilder();
        for (String rawLine : text.split("\\n")) {
            String line = rawLine.trim();
            while (line.startsWith("|")) {
                line = line.substring(1).trim();
            }
            while (line.endsWith("|")) {
                line = line.substring(0, line.length() - 1).trim();
            }
            if (!line.isEmpty()) {
                if (result.length() > 0) {
                    result.append('\n');
                }
                result.append(line);
            }
        }
        return result.toString();
    }

    static String flattenJiraText(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isTextual() || value.isNumber() || value.isBoolean()) {
            return htmlToText(value.asText());
        }
        if (value.isArray()) {
            List<String> parts = new ArrayList<String>();
            for (JsonNode item : value) {
                String text = flattenJiraText(item);
                if (!text.isEmpty()) {
                    parts.add(text);
                }
            }
            return join(parts, "\n");
        }
        if (value.isObject()) {
            JsonNode text = value.get("text");
            if (text != null && text.isTextual()) {
                return text.asText();
            }
            return flattenJiraText(value.get("content"));
        }
        return "";
    }

    static List<String> extractJiraKeys(String text) {
        Set<String> result = new LinkedHashSet<String>();
        Matcher matcher = JIRA_KEY.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String key = matcher.group(1).toUpperCase(Locale.ROOT);
            if (!key.startsWith("MGSN-")) {
                result.add(key);
            }
        }
        return sorted(result);
    }

    static List<String> extractMgsnKeys(String text) {
        Set<String> result = new LinkedHashSet<String>();
        Matcher matcher = MGSN_KEY.matcher(text == null ? "" : text);
        while (matcher.find()) {
            result.add(matcher.group(1).replaceAll("\\s+", "").toUpperCase(Locale.ROOT));
        }
        return sorted(result);
    }

    static List<String> extractUrls(String text) {
        Set<String> result = new LinkedHashSet<String>();
        Matcher matcher = URL.matcher(text == null ? "" : text);
        while (matcher.find()) {
            result.add(matcher.group().replaceAll("[\\].,]+$", ""));
        }
        return sorted(result);
    }

    static List<String> extractXpaths(String text) {
        Set<String> result = new LinkedHashSet<String>();
        Matcher matcher = XPATH.matcher(text == null ? "" : text);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return sorted(result);
    }

    static List<String> splitTableLine(String line) {
        if (line == null || !line.contains("|")) {
            return Collections.emptyList();
        }
        List<String> cells = new ArrayList<String>();
        String[] parts = line.replaceAll("^\\|+", "").replaceAll("\\|+$", "").split("\\|");
        for (String part : parts) {
            String cell = part.trim();
            if (!cell.isEmpty()) {
                cells.add(cell);
            }
        }
        return cells;
    }

    static Boolean normalizeBool(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("да".equals(normalized) || "yes".equals(normalized) || "true".equals(normalized) || "1".equals(normalized)) {
            return Boolean.TRUE;
        }
        if ("нет".equals(normalized) || "no".equals(normalized) || "false".equals(normalized) || "0".equals(normalized) || "-".equals(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }

    static String compact(String text, int maxChars) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars - 3)).trim() + "...";
    }

    static String join(List<String> values, String delimiter) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(delimiter);
            }
            result.append(value);
        }
        return result.toString();
    }

    private static String unescape(String value) {
        return value
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }

    private static List<String> sorted(Set<String> values) {
        List<String> result = new ArrayList<String>(values);
        Collections.sort(result);
        return result;
    }
}
