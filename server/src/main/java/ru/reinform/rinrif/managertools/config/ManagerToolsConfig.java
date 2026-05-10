package ru.reinform.rinrif.managertools.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecService;
import ru.reinform.rinrif.managertools.core.ManagerToolsService;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class ManagerToolsConfig {
    @Bean
    public ManagerToolsService managerToolsService(Environment environment) {
        Map<String, String> config = new LinkedHashMap<String, String>();
        put(config, "MANAGER_TOOLS_STORAGE_ROOT", environment.getProperty("manager-tools.storage-root"));
        put(config, "MANAGER_TOOLS_ENV_FILE", environment.getProperty("manager-tools.env-file"));
        put(config, "GITLAB_BASE_URL", environment.getProperty("manager-tools.gitlab.base-url"));
        put(config, "GITLAB_PAT", environment.getProperty("manager-tools.gitlab.pat"));
        put(config, "GIT_COMMAND_TIMEOUT_MS", environment.getProperty("manager-tools.git.command-timeout-ms"));
        put(config, "SEARCH_MAX_QUERY_LENGTH", environment.getProperty("manager-tools.search.max-query-length"));
        put(config, "SEARCH_DEFAULT_MAX_COMMITS", environment.getProperty("manager-tools.search.default-max-commits"));
        put(config, "SEARCH_SCAN_LIMIT", environment.getProperty("manager-tools.search.scan-limit"));
        return new ManagerToolsService(config);
    }

    @Bean
    public Atr2SpecService atr2SpecService(Environment environment) {
        Map<String, String> config = new LinkedHashMap<String, String>();
        put(config, "MANAGER_TOOLS_STORAGE_ROOT", environment.getProperty("manager-tools.storage-root"));
        put(config, "MANAGER_TOOLS_ENV_FILE", environment.getProperty("manager-tools.env-file"));
        put(config, "MANAGER_TOOLS_ATR2SPEC_ENV_FILE", environment.getProperty("manager-tools.atr2spec.env-file"));
        put(config, "CONFLUENCE_BASE_URL", environment.getProperty("manager-tools.atr2spec.confluence.base-url"));
        put(config, "CONFLUENCE_USER", environment.getProperty("manager-tools.atr2spec.confluence.user"));
        put(config, "CONFLUENCE_TOKEN", environment.getProperty("manager-tools.atr2spec.confluence.token"));
        put(config, "JIRA_BASE_URL", environment.getProperty("manager-tools.atr2spec.jira.base-url"));
        put(config, "JIRA_USER", environment.getProperty("manager-tools.atr2spec.jira.user"));
        put(config, "JIRA_TOKEN", environment.getProperty("manager-tools.atr2spec.jira.token"));
        put(config, "ATR2SPEC_VERIFY_SSL", environment.getProperty("manager-tools.atr2spec.verify-ssl"));
        put(config, "ATR2SPEC_HTTP_TIMEOUT_MS", environment.getProperty("manager-tools.atr2spec.http-timeout-ms"));
        return new Atr2SpecService(config);
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            target.put(key, value);
        }
    }
}
