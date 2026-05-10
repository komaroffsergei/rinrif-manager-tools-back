package ru.reinform.rinrif.managertools.atr2spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

class Atr2SpecConfig {
    final Path storageRoot;
    final Path atr2SpecRoot;
    final String confluenceBaseUrl;
    final String confluenceUser;
    final String confluenceToken;
    final String jiraBaseUrl;
    final String jiraUser;
    final String jiraToken;
    final boolean verifySsl;
    final int httpTimeoutMs;

    private Atr2SpecConfig(Path storageRoot, String confluenceBaseUrl, String confluenceUser, String confluenceToken,
                          String jiraBaseUrl, String jiraUser, String jiraToken, boolean verifySsl, int httpTimeoutMs) {
        this.storageRoot = storageRoot.toAbsolutePath();
        this.atr2SpecRoot = this.storageRoot.resolve("atr2spec");
        this.confluenceBaseUrl = trimRight(confluenceBaseUrl, "/");
        this.confluenceUser = emptyToNull(confluenceUser);
        this.confluenceToken = emptyToNull(confluenceToken);
        this.jiraBaseUrl = trimRight(jiraBaseUrl, "/");
        this.jiraUser = emptyToNull(jiraUser);
        this.jiraToken = emptyToNull(jiraToken);
        this.verifySsl = verifySsl;
        this.httpTimeoutMs = httpTimeoutMs;
    }

    static Atr2SpecConfig load(Map<String, String> externalValues) {
        Map<String, String> atrEnv = readEnvFile(readConfig(externalValues, null, "MANAGER_TOOLS_ATR2SPEC_ENV_FILE", null));
        Map<String, String> defaultEnv = readEnvFile(readConfig(externalValues, null, "MANAGER_TOOLS_ENV_FILE", ".env"));
        String jiraUser = readConfig(externalValues, atrEnv, defaultEnv, "JIRA_USER", "JIRA_USERNAME", null);
        String jiraToken = readConfig(externalValues, atrEnv, defaultEnv, "JIRA_TOKEN", "JIRA_PASSWORD", null);
        String confluenceUser = readConfig(externalValues, atrEnv, defaultEnv, "CONFLUENCE_USER", "CONFLUENCE_USERNAME", jiraUser);
        String confluenceToken = readConfig(externalValues, atrEnv, defaultEnv, "CONFLUENCE_TOKEN", "CONFLUENCE_PASSWORD", jiraToken);
        boolean verifySsl = Boolean.parseBoolean(readConfig(
                externalValues,
                atrEnv,
                defaultEnv,
                "ATR2SPEC_VERIFY_SSL",
                "CONFLUENCE_VERIFY_SSL",
                "JIRA_VERIFY_SSL",
                "true"
        ));
        return new Atr2SpecConfig(
                Paths.get(readConfig(externalValues, atrEnv, defaultEnv, "MANAGER_TOOLS_STORAGE_ROOT", "storage")),
                readConfig(externalValues, atrEnv, defaultEnv, "CONFLUENCE_BASE_URL", "https://wiki.reinform-int.ru"),
                confluenceUser,
                confluenceToken,
                readConfig(externalValues, atrEnv, defaultEnv, "JIRA_BASE_URL", "https://jira.reinform-int.ru"),
                jiraUser,
                jiraToken,
                verifySsl,
                Integer.parseInt(readConfig(externalValues, atrEnv, defaultEnv, "ATR2SPEC_HTTP_TIMEOUT_MS", "30000"))
        );
    }

    private static String readConfig(Map<String, String> externalValues, Map<String, String> envFileValues, String name, String defaultValue) {
        return readConfig(externalValues, envFileValues, Collections.<String, String>emptyMap(), name, defaultValue);
    }

    private static String readConfig(Map<String, String> externalValues, Map<String, String> primaryEnv, Map<String, String> secondaryEnv, String name, String defaultValue) {
        return readConfig(externalValues, primaryEnv, secondaryEnv, name, null, null, defaultValue);
    }

    private static String readConfig(Map<String, String> externalValues, Map<String, String> primaryEnv, Map<String, String> secondaryEnv,
                                     String firstName, String secondName, String defaultValue) {
        return readConfig(externalValues, primaryEnv, secondaryEnv, firstName, secondName, null, defaultValue);
    }

    private static String readConfig(Map<String, String> externalValues, Map<String, String> primaryEnv, Map<String, String> secondaryEnv,
                                     String firstName, String secondName, String thirdName, String defaultValue) {
        String[] names = compactNames(firstName, secondName, thirdName);
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
        value = readFirstFromMap(primaryEnv, names);
        if (value != null) {
            return value;
        }
        value = readFirstFromMap(secondaryEnv, names);
        return value == null ? defaultValue : value;
    }

    private static String[] compactNames(String firstName, String secondName, String thirdName) {
        if (secondName == null) {
            return new String[]{firstName};
        }
        if (thirdName == null) {
            return new String[]{firstName, secondName};
        }
        return new String[]{firstName, secondName, thirdName};
    }

    private static Map<String, String> readEnvFile(String configuredPath) {
        if (configuredPath == null || configuredPath.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Path envFile = Paths.get(configuredPath.trim());
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

    private static boolean isUsableValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        String trimmed = value.trim();
        return !(trimmed.startsWith("${") && trimmed.endsWith("}"));
    }

    private static String emptyToNull(String value) {
        return isUsableValue(value) ? value.trim() : null;
    }

    private static String trimRight(String value, String suffix) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith(suffix)) {
            result = result.substring(0, result.length() - suffix.length());
        }
        return result;
    }
}
