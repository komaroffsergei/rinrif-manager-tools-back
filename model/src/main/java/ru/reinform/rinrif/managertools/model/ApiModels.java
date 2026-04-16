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
        search,
        update,
        delete
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

        public AddRepositoryResponse() {
        }

        public AddRepositoryResponse(RepositorySummary repository) {
            this.repository = repository;
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
}
