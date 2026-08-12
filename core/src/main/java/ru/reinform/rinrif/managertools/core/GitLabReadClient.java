package ru.reinform.rinrif.managertools.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceMergeRequest;
import ru.reinform.rinrif.managertools.model.ApiModels.RepositoryRecord;
import ru.reinform.rinrif.managertools.model.ApiModels.TraceConfidence;
import ru.reinform.rinrif.managertools.model.ApiModels.TraceSource;
import ru.reinform.rinrif.managertools.model.ApiModels.TraceSourceKind;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class GitLabReadClient {
    private final AppConfig config;
    private final ObjectMapper objectMapper;

    GitLabReadClient(AppConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    List<ReleaseTraceMergeRequest> findMergeRequests(RepositoryRecord repository, List<String> commitShas, List<String> taskKeys) {
        List<ReleaseTraceMergeRequest> result = new ArrayList<ReleaseTraceMergeRequest>();
        if (config.gitLabBaseUrl == null || config.gitLabPat == null) {
            return result;
        }
        String project = JiraReadClient.encode(projectPath(repository));
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("PRIVATE-TOKEN", config.gitLabPat);
        Set<String> seen = new LinkedHashSet<String>();
        boolean successfulRequest = false;
        RuntimeException lastError = null;
        for (String sha : commitShas) {
            try {
                JsonNode response = get(apiBase() + "/projects/" + project + "/repository/commits/"
                        + JiraReadClient.encode(sha) + "/merge_requests", headers);
                successfulRequest = true;
                append(result, response, taskKeys, seen, "MR найден по коммиту " + shortSha(sha));
            } catch (RuntimeException error) {
                lastError = error;
            }
        }
        for (String taskKey : taskKeys) {
            try {
                JsonNode response = get(apiBase() + "/projects/" + project + "/merge_requests?scope=all&state=all&per_page=100&search="
                        + JiraReadClient.encode(taskKey) + "&in=title,description", headers);
                successfulRequest = true;
                append(result, response, taskKeys, seen, "MR найден по коду Jira " + taskKey);
            } catch (RuntimeException error) {
                lastError = error;
            }
        }
        if (!successfulRequest && lastError != null) {
            throw lastError;
        }
        return result;
    }

    private void append(List<ReleaseTraceMergeRequest> target, JsonNode response, List<String> taskKeys,
                        Set<String> seen, String provenance) {
        if (response == null || !response.isArray()) {
            return;
        }
        for (JsonNode item : response) {
            String identity = JiraReadClient.textAt(item, "web_url");
            if (identity.isEmpty()) {
                identity = JiraReadClient.textAt(item, "id");
            }
            if (identity.isEmpty() || !seen.add(identity)) {
                continue;
            }
            ReleaseTraceMergeRequest mr = new ReleaseTraceMergeRequest();
            mr.id = JiraReadClient.textAt(item, "id");
            mr.iid = item.path("iid").isNumber() ? item.path("iid").asInt() : null;
            mr.title = JiraReadClient.textAt(item, "title");
            mr.description = CoreUtils.emptyToNull(JiraReadClient.textAt(item, "description"));
            mr.url = JiraReadClient.textAt(item, "web_url");
            mr.state = JiraReadClient.textAt(item, "state");
            mr.sourceBranch = JiraReadClient.textAt(item, "source_branch");
            mr.targetBranch = JiraReadClient.textAt(item, "target_branch");
            mr.mergedAt = CoreUtils.emptyToNull(JiraReadClient.textAt(item, "merged_at"));
            String searchable = (CoreUtils.safe(mr.title) + "\n" + CoreUtils.safe(mr.description)).toUpperCase();
            for (String taskKey : taskKeys) {
                if (searchable.contains(taskKey.toUpperCase())) {
                    mr.matchedTaskKeys.add(taskKey);
                }
            }
            mr.source = new TraceSource(TraceSourceKind.merge_request, "GitLab MR", mr.url, provenance);
            mr.confidence = "merged".equalsIgnoreCase(mr.state) ? TraceConfidence.confirmed : TraceConfidence.attention;
            target.add(mr);
        }
    }

    private JsonNode get(String url, Map<String, String> headers) {
        return ReadOnlyJsonClient.getJson(url, headers, config.verifySsl, config.httpTimeoutMs,
                objectMapper, "GITLAB_REQUEST_FAILED");
    }

    private String apiBase() {
        String base = config.gitLabBaseUrl.replaceAll("/+$", "");
        return base.endsWith("/api/v4") ? base : base + "/api/v4";
    }

    private String projectPath(RepositoryRecord repository) {
        try {
            URL repositoryUrl = new URL(repository.normalizedUrl);
            URL configuredBase = new URL(config.gitLabBaseUrl);
            String path = repositoryUrl.getPath().replaceAll("^/+|/+$", "");
            String basePath = configuredBase.getPath().replaceAll("^/+|/+$", "");
            if (path.startsWith(basePath + "/")) {
                path = path.substring(basePath.length() + 1);
            }
            return path.replaceAll("(?i)\\.git$", "");
        } catch (Exception error) {
            return CoreUtils.safe(repository.name).replaceAll("(?i)\\.git$", "");
        }
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() <= 8 ? CoreUtils.safe(sha) : sha.substring(0, 8);
    }
}
