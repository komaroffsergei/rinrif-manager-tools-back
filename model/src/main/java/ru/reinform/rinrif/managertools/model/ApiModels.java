package ru.reinform.rinrif.managertools.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ApiModels {
    private ApiModels() {
    }

    public enum RepositoryStatus {
        ready,
        cloning,
        updating,
        queued,
        searching,
        deleting,
        broken,
        auth_error
    }

    public enum JobStatus {
        queued,
        running,
        updating_repository,
        searching_commits,
        building_diff_links,
        done,
        failed,
        deleted
    }

    public enum JobType {
        clone,
        search,
        update,
        delete
    }

    public enum ReleaseTraceStatus {
        queued,
        reading_jira,
        resolving_targets,
        updating_repositories,
        finding_release,
        searching_commits,
        building_diff,
        finding_merge_requests,
        done,
        partial,
        failed
    }

    public enum TraceConfidence {
        confirmed,
        commit_link,
        attention,
        unconfirmed,
        technical
    }

    public enum TraceSourceKind {
        jira_description,
        jira_comment,
        jira_issue_link,
        jira_remote_link,
        merge_request,
        commit_message,
        git_ancestry,
        final_diff,
        manual_override
    }

    public static class AppError {
        public String code;
        public String message;
        public String details;

        public AppError() {
        }

        public AppError(String code, String message, String details) {
            this.code = code;
            this.message = message;
            this.details = details;
        }
    }

    public static class RepositoryFlags {
        public boolean pendingDelete;
    }

    public static class RepositoryRecord {
        public String id;
        public String name;
        public String url;
        public String normalizedUrl;
        public String host;
        public String provider = "gitlab";
        public String localPath;
        public RepositoryStatus status;
        public String createdAt;
        public String updatedAt;
        public String lastFetchedAt;
        public String lastUsedAt;
        public Long sizeBytes;
        public RepositoryFlags flags = new RepositoryFlags();
    }

    public static class FileLinkResult {
        public String kind;
        public String url;
        public Integer startLine;
        public Integer endLine;
        public String reason;
    }

    public static class FileSearchResult {
        public String path;
        public List<FileLinkResult> links = new ArrayList<FileLinkResult>();
    }

    public static class CommitSearchResult {
        public String sha;
        public String subject;
        public String body;
        public String authorName;
        public String authoredAt;
        public int score;
        public String commitUrl;
        public List<FileSearchResult> files = new ArrayList<FileSearchResult>();
    }

    public static class SearchResult {
        public List<CommitSearchResult> items = new ArrayList<CommitSearchResult>();
    }

    public static class SearchJobRecord {
        public String jobId;
        public JobType type;
        public String repoId;
        public JobStatus status;
        public int queuePosition;
        public String message;
        public Map<String, Object> payload = new LinkedHashMap<String, Object>();
        public SearchResult result;
        public AppError error;
        public String createdAt;
        public String updatedAt;
    }

    public static class SearchRequestPayload {
        public String repoId;
        public String ref;
        public String query;
        public String dateFrom;
        public String dateTo;
        public Boolean includeMerges;
        public Boolean firstParent;
        public Integer maxCommits;
        public List<String> excludedFilePatterns = new ArrayList<String>();
    }

    public static class AddRepositoryRequest {
        public String url;
    }

    public static class RepositorySummary {
        public String id;
        public String name;
        public RepositoryStatus status;

        public RepositorySummary() {
        }

        public RepositorySummary(String id, String name, RepositoryStatus status) {
            this.id = id;
            this.name = name;
            this.status = status;
        }
    }

    public static class AddRepositoryResponse {
        public RepositorySummary repository;
        public String jobId;
        public Integer queuePosition;
        public Boolean existing;

        public AddRepositoryResponse() {
        }

        public AddRepositoryResponse(RepositorySummary repository) {
            this.repository = repository;
        }

        public AddRepositoryResponse(RepositorySummary repository, String jobId, Integer queuePosition, Boolean existing) {
            this.repository = repository;
            this.jobId = jobId;
            this.queuePosition = queuePosition;
            this.existing = existing;
        }
    }

    public static class QueuedJobResponse {
        public String jobId;
        public String status = "queued";
        public int queuePosition;

        public QueuedJobResponse() {
        }

        public QueuedJobResponse(String jobId, int queuePosition) {
            this.jobId = jobId;
            this.queuePosition = queuePosition;
        }
    }

    public static class DeleteResponse {
        public boolean ok = true;
    }

    public static class HealthResponse {
        public boolean ok = true;
        public String timestamp;

        public HealthResponse() {
        }

        public HealthResponse(String timestamp) {
            this.timestamp = timestamp;
        }
    }

    public static class TraceSource {
        public TraceSourceKind kind;
        public String label;
        public String url;
        public String details;

        public TraceSource() {
        }

        public TraceSource(TraceSourceKind kind, String label, String url, String details) {
            this.kind = kind;
            this.label = label;
            this.url = url;
            this.details = details;
        }
    }

    public static class JiraCommentData {
        public String author;
        public String created;
        public String body;
    }

    public static class JiraIssueLinkData {
        public String key;
        public String title;
        public String relationship;
        public String direction;
        public String url;
    }

    public static class JiraRemoteLinkData {
        public String title;
        public String url;
    }

    public static class JiraIssueData {
        public String key;
        public String title;
        public String description;
        public String status;
        public String issueType;
        public String projectKey;
        public String url;
        public List<String> labels = new ArrayList<String>();
        public List<String> components = new ArrayList<String>();
        public List<JiraCommentData> comments = new ArrayList<JiraCommentData>();
        public List<JiraIssueLinkData> issueLinks = new ArrayList<JiraIssueLinkData>();
        public List<JiraRemoteLinkData> remoteLinks = new ArrayList<JiraRemoteLinkData>();
    }

    public static class ReleaseTraceRunRequest {
        public String jira;
        public String jiraKey;
        public ReleaseTraceOverrides overrides;
    }

    public static class ReleaseTraceOverrides {
        public List<String> taskKeys = new ArrayList<String>();
        public List<ReleaseTraceRepositoryOverride> repositories = new ArrayList<ReleaseTraceRepositoryOverride>();
    }

    public static class ReleaseTraceRepositoryOverride {
        public String application;
        public String repoId;
        public String ref;
        public String baseSha;
        public String targetSha;
    }

    public static class ReleaseTraceQueuedResponse {
        public String runId;
        public ReleaseTraceStatus status = ReleaseTraceStatus.queued;
        public int queuePosition;

        public ReleaseTraceQueuedResponse() {
        }

        public ReleaseTraceQueuedResponse(String runId) {
            this.runId = runId;
        }
    }

    public static class ReleaseTraceRunRecord {
        public String runId;
        public ReleaseTraceStatus status;
        public int progress;
        public String step;
        public String message;
        public ReleaseTraceRunRequest request;
        public ReleaseTraceJiraIssue release;
        public List<ReleaseTraceApplication> applications = new ArrayList<ReleaseTraceApplication>();
        public List<String> warnings = new ArrayList<String>();
        public AppError error;
        public String createdAt;
        public String updatedAt;
    }

    public static class ReleaseTraceJiraIssue {
        public String key;
        public String title;
        public String url;
        public String status;
        public List<String> releaseKeys = new ArrayList<String>();
        public List<TraceSource> sources = new ArrayList<TraceSource>();
    }

    public static class ReleaseTraceTask {
        public String key;
        public String title;
        public String url;
        public String status;
        public List<TraceSource> sources = new ArrayList<TraceSource>();
    }

    public static class ReleaseTraceRepository {
        public String id;
        public String name;
        public String url;
    }

    public static class ReleaseTraceReleaseCandidate {
        public String sha;
        public String version;
        public String subject;
        public String authoredAt;
        public boolean selected;
    }

    public static class ReleaseTraceSelectedRelease extends ReleaseTraceReleaseCandidate {
        public String baseSha;
        public String targetSha;
    }

    public static class ReleaseTraceMergeRequest {
        public String id;
        public Integer iid;
        public String title;
        public String description;
        public String url;
        public String state;
        public String sourceBranch;
        public String targetBranch;
        public String mergedAt;
        public List<String> matchedTaskKeys = new ArrayList<String>();
        public TraceSource source;
        public TraceConfidence confidence;
    }

    public static class ReleaseTraceCommit {
        public String sha;
        public String shortSha;
        public String subject;
        public String body;
        public String authorName;
        public String authoredAt;
        public String url;
        public boolean historical;
        public List<String> matchedTaskKeys = new ArrayList<String>();
        public TraceSource source;
        public TraceConfidence confidence;
    }

    public static class ReleaseTraceFile {
        public String path;
        public String url;
        public boolean technical;
        public TraceSource source;
        public TraceConfidence confidence;
        public List<FileLinkResult> links = new ArrayList<FileLinkResult>();
    }

    public static class ReleaseTraceApplication {
        public String application;
        public String status;
        public ReleaseTraceRepository repository;
        public String ref;
        public List<ReleaseTraceReleaseCandidate> releaseCandidates = new ArrayList<ReleaseTraceReleaseCandidate>();
        public ReleaseTraceSelectedRelease selectedRelease;
        public List<ReleaseTraceTask> tasks = new ArrayList<ReleaseTraceTask>();
        public List<ReleaseTraceMergeRequest> mergeRequests = new ArrayList<ReleaseTraceMergeRequest>();
        public List<ReleaseTraceCommit> commits = new ArrayList<ReleaseTraceCommit>();
        public List<ReleaseTraceCommit> historicalCommits = new ArrayList<ReleaseTraceCommit>();
        public List<ReleaseTraceFile> files = new ArrayList<ReleaseTraceFile>();
        public List<ReleaseTraceFile> technicalFiles = new ArrayList<ReleaseTraceFile>();
        public List<String> warnings = new ArrayList<String>();
        public TraceConfidence confidence;
    }

    public static class RepositoryRef {
        public String name;
        public String fullName;
        public String type;
        public String sha;
    }

    public static class RepositoryRefsResponse {
        public String repositoryId;
        public String defaultRef;
        public List<RepositoryRef> items = new ArrayList<RepositoryRef>();
    }
}
