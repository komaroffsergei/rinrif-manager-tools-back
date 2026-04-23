package ru.reinform.rinrif.managertools.core;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

class AppConfig {
    final Path storageRoot;
    final long gitCommandTimeoutMs;
    final int searchMaxQueryLength;
    final int searchDefaultMaxCommits;
    final int searchScanLimit;
    final String gitLabBaseUrl;
    final String gitLabHost;
    final String gitLabPat;

    AppConfig(Path storageRoot, long gitCommandTimeoutMs, int searchMaxQueryLength, int searchDefaultMaxCommits, int searchScanLimit, String gitLabBaseUrl, String gitLabPat) {
        this.storageRoot = storageRoot.toAbsolutePath();
        this.gitCommandTimeoutMs = gitCommandTimeoutMs;
        this.searchMaxQueryLength = searchMaxQueryLength;
        this.searchDefaultMaxCommits = searchDefaultMaxCommits;
        this.searchScanLimit = searchScanLimit;
        this.gitLabBaseUrl = emptyToNull(gitLabBaseUrl);
        this.gitLabPat = emptyToNull(gitLabPat);
        this.gitLabHost = resolveHost(this.gitLabBaseUrl);
    }

    static AppConfig load() {
        return load(null);
    }

    static AppConfig load(Map<String, String> externalValues) {
        Map<String, String> envFileValues = readEnvFile(externalValues);
        return new AppConfig(
                Paths.get(readConfig(externalValues, envFileValues, "MANAGER_TOOLS_STORAGE_ROOT", "storage")),
                Long.parseLong(readConfig(externalValues, envFileValues, "GIT_COMMAND_TIMEOUT_MS", "300000")),
                Integer.parseInt(readConfig(externalValues, envFileValues, "SEARCH_MAX_QUERY_LENGTH", "512")),
                Integer.parseInt(readConfig(externalValues, envFileValues, "SEARCH_DEFAULT_MAX_COMMITS", "30")),
                Integer.parseInt(readConfig(externalValues, envFileValues, "SEARCH_SCAN_LIMIT", "1000")),
                readConfig(externalValues, envFileValues, "GITLAB_BASE_URL", null),
                readGitLabPat(externalValues, envFileValues)
        );
    }

    private static String readConfig(Map<String, String> externalValues, Map<String, String> envFileValues, String name, String defaultValue) {
        String value = readHighPrecedenceConfig(externalValues, name);
        if (value != null) {
            return value;
        }
        if (envFileValues != null) {
            String envFileValue = readFirstFromMap(envFileValues, name);
            if (envFileValue != null) {
                return envFileValue;
            }
        }
        return defaultValue;
    }

    private static String readHighPrecedenceConfig(Map<String, String> externalValues, String name) {
        String propertyValue = readFirstSystemProperty(name);
        if (propertyValue != null) {
            return propertyValue;
        }
        String envValue = readFirstFromMap(System.getenv(), name);
        if (envValue != null) {
            return envValue;
        }
        String externalValue = readFirstFromMap(externalValues, name);
        if (externalValue != null) {
            return externalValue;
        }
        return null;
    }

    private static String readGitLabPat(Map<String, String> externalValues, Map<String, String> envFileValues) {
        String[] names = new String[]{"GITLAB_PAT", "MANAGER_TOOLS_GITLAB_PAT"};
        String value = readFirstSystemProperty(names);
        if (value != null) {
            return value;
        }
        value = readFirstFromMap(System.getenv(), names);
        if (value != null) {
            return value;
        }
        value = readFirstFromMap(externalValues, names);
        if (value != null) {
            return value;
        }
        return readFirstFromMap(envFileValues, names);
    }

    private static String readFirstSystemProperty(String... names) {
        for (String name : names) {
            String value = System.getProperty(name);
            if (isUsableValue(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String readFirstFromMap(Map<String, String> values, String... names) {
        if (values == null) {
            return null;
        }
        for (String name : names) {
            String value = values.get(name);
            if (isUsableValue(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static Map<String, String> readEnvFile(Map<String, String> externalValues) {
        String configuredPath = readHighPrecedenceConfig(externalValues, "MANAGER_TOOLS_ENV_FILE");
        Path envFile = configuredPath == null ? Paths.get(".env") : Paths.get(configuredPath);
        if (!Files.isRegularFile(envFile)) {
            return Collections.emptyMap();
        }
        Map<String, String> values = new LinkedHashMap<String, String>();
        try {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                readEnvLine(values, line);
            }
        } catch (IOException ignored) {
            return Collections.emptyMap();
        }
        return values;
    }

    private static void readEnvLine(Map<String, String> values, String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }
        if (trimmed.startsWith("export ")) {
            trimmed = trimmed.substring("export ".length()).trim();
        }
        int separator = trimmed.indexOf('=');
        if (separator <= 0) {
            return;
        }
        String name = trimmed.substring(0, separator).trim();
        String value = parseEnvValue(trimmed.substring(separator + 1));
        if (!name.isEmpty() && isUsableValue(value)) {
            values.put(name, value.trim());
        }
    }

    private static String parseEnvValue(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static boolean isUsableValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        String trimmed = value.trim();
        return !(trimmed.startsWith("${") && trimmed.endsWith("}"));
    }

    private static String resolveHost(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        try {
            URL url = new URL(baseUrl);
            String host = url.getHost().toLowerCase(Locale.ROOT);
            return url.getPort() > 0 ? host + ":" + url.getPort() : host;
        } catch (MalformedURLException error) {
            return null;
        }
    }

    private static String emptyToNull(String value) {
        return isUsableValue(value) ? value.trim() : null;
    }
}
