package ru.reinform.rinrif.managertools.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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

    private static void put(Map<String, String> target, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            target.put(key, value);
        }
    }
}
