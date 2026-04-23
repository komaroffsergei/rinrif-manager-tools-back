package ru.reinform.rinrif.managertools.core;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
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
        return new AppConfig(
                Paths.get(readConfig(externalValues, "MANAGER_TOOLS_STORAGE_ROOT", "storage")),
                Long.parseLong(readConfig(externalValues, "GIT_COMMAND_TIMEOUT_MS", "300000")),
                Integer.parseInt(readConfig(externalValues, "SEARCH_MAX_QUERY_LENGTH", "512")),
                Integer.parseInt(readConfig(externalValues, "SEARCH_DEFAULT_MAX_COMMITS", "30")),
                Integer.parseInt(readConfig(externalValues, "SEARCH_SCAN_LIMIT", "1000")),
                readConfig(externalValues, "GITLAB_BASE_URL", null),
                readGitLabPat(externalValues)
        );
    }

    private static String readConfig(Map<String, String> externalValues, String name, String defaultValue) {
        String propertyValue = System.getProperty(name);
        if (isUsableValue(propertyValue)) {
            return propertyValue.trim();
        }
        String envValue = System.getenv(name);
        if (isUsableValue(envValue)) {
            return envValue.trim();
        }
        if (externalValues != null) {
            String externalValue = externalValues.get(name);
            if (isUsableValue(externalValue)) {
                return externalValue.trim();
            }
        }
        return defaultValue;
    }

    private static String readGitLabPat(Map<String, String> externalValues) {
        String value = readConfig(externalValues, "GITLAB_PAT", null);
        return value == null ? readConfig(externalValues, "MANAGER_TOOLS_GITLAB_PAT", null) : value;
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
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
