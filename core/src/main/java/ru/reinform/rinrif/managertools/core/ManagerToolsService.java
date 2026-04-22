package ru.reinform.rinrif.managertools.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.reinform.rinrif.managertools.model.ApiModels.AddRepositoryResponse;
import ru.reinform.rinrif.managertools.model.ApiModels.JobStatus;
import ru.reinform.rinrif.managertools.model.ApiModels.JobType;
import ru.reinform.rinrif.managertools.model.ApiModels.QueuedJobResponse;
import ru.reinform.rinrif.managertools.model.ApiModels.RepositoryRecord;
import ru.reinform.rinrif.managertools.model.ApiModels.RepositorySummary;
import ru.reinform.rinrif.managertools.model.ApiModels.RepositoryStatus;
import ru.reinform.rinrif.managertools.model.ApiModels.SearchJobRecord;
import ru.reinform.rinrif.managertools.model.ApiModels.SearchRequestPayload;
import ru.reinform.rinrif.managertools.model.ApiModels.SearchResult;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ManagerToolsService {
    private final AppConfig config;
    private final RepositoryRegistry repositoryRegistry;
    private final JobsStore jobsStore;
    private final RepoQueueManager queueManager;
    private final RepositoryManager repositoryManager;
    private final SearchService searchService;

    public ManagerToolsService() {
        this(AppConfig.load());
    }

    public ManagerToolsService(Map<String, String> externalConfig) {
        this(AppConfig.load(externalConfig));
    }

    ManagerToolsService(AppConfig config) {
        this.config = config;
        ObjectMapper objectMapper = new ObjectMapper();
        this.repositoryRegistry = new RepositoryRegistry(config.storageRoot, objectMapper);
        this.jobsStore = new JobsStore(config.storageRoot, objectMapper);
        this.queueManager = new RepoQueueManager(jobsStore);
        GitAuth gitAuth = new GitAuth(config);
        GitRunner gitRunner = new GitRunner(config, gitAuth);
        this.repositoryManager = new RepositoryManager(
                new GitMirrorService(gitRunner, gitAuth),
                new GitRefsService(gitRunner),
                new GitLogService(gitRunner)
        );
        this.searchService = new SearchService(config, repositoryRegistry, repositoryManager, queueManager, jobsStore);
        this.repositoryRegistry.ensureInitialized();
    }

    public List<RepositoryRecord> listRepositories() {
        List<RepositoryRecord> repositories = repositoryRegistry.listRepositories();
        Collections.sort(repositories, new Comparator<RepositoryRecord>() {
            @Override
            public int compare(RepositoryRecord left, RepositoryRecord right) {
                return CoreUtils.safe(left.name).compareToIgnoreCase(CoreUtils.safe(right.name));
            }
        });
        return repositories;
    }

    public RepositoryRecord getRepository(String repoId) {
        return repositoryRegistry.getRepository(repoId);
    }

    public AddRepositoryResponse addRepository(String inputUrl) {
        NormalizedRepositoryUrl normalized = normalizeRepositoryUrl(inputUrl, config);
        RepositoryRecord existing = repositoryRegistry.findByNormalizedUrl(normalized.normalizedUrl);
        if (existing != null) {
            return new AddRepositoryResponse(toSummary(existing), null, null, true);
        }

        String now = CoreUtils.nowIso();
        final RepositoryRecord repository = new RepositoryRecord();
        repository.id = CoreUtils.createIdentifier("repo");
        repository.name = normalized.name;
        repository.url = normalized.url;
        repository.normalizedUrl = normalized.normalizedUrl;
        repository.host = normalized.host;
        repository.provider = "gitlab";
        repository.localPath = config.storageRoot.resolve("repos").resolve(repository.id + ".git").toAbsolutePath().toString();
        repository.status = RepositoryStatus.cloning;
        repository.createdAt = now;
        repository.updatedAt = now;

        repositoryRegistry.createRepository(repository);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("url", repository.normalizedUrl);
        final SearchJobRecord job = jobsStore.createJob(JobType.clone, repository.id, payload, "Clone queued");
        RepoQueueManager.QueueTaskHandle handle = queueManager.enqueue(repository.id, job.jobId, new RepoQueueManager.RunnableTask() {
            @Override
            public void run() {
                executeClone(repository.id, job.jobId);
            }
        });
        return new AddRepositoryResponse(toSummary(repository), job.jobId, handle.queuePosition, false);
    }

    public QueuedJobResponse startUpdate(String repoId) {
        final RepositoryRecord repository = repositoryRegistry.getRepository(repoId);
        final SearchJobRecord job = jobsStore.createJob(JobType.update, repository.id, new LinkedHashMap<String, Object>(), "Update queued");
        RepoQueueManager.QueueTaskHandle handle = queueManager.enqueue(repository.id, job.jobId, new RepoQueueManager.RunnableTask() {
            @Override
            public void run() {
                executeUpdate(repository.id, job.jobId);
            }
        });
        if (handle.queuePosition > 0) {
            tryMarkRepositoryQueued(repository.id);
        }
        return new QueuedJobResponse(job.jobId, handle.queuePosition);
    }

    public void deleteRepository(String repoId) {
        final RepositoryRecord repository = repositoryRegistry.getRepository(repoId);
        final SearchJobRecord job = jobsStore.createJob(JobType.delete, repository.id, new LinkedHashMap<String, Object>(), "Delete queued");
        RepoQueueManager.QueueTaskHandle handle = queueManager.enqueue(repository.id, job.jobId, new RepoQueueManager.RunnableTask() {
            @Override
            public void run() {
                executeDelete(repository.id, job.jobId);
            }
        });
        if (handle.queuePosition > 0) {
            tryMarkRepositoryQueued(repository.id);
        }
        await(handle.completion);
    }

    public QueuedJobResponse startSearch(SearchRequestPayload input) {
        return searchService.startSearch(input);
    }

    public SearchJobRecord getJob(String jobId) {
        return jobsStore.getJob(jobId);
    }

    private void executeClone(String repoId, String jobId) {
        repositoryRegistry.updateRepositoryStatus(repoId, RepositoryStatus.cloning);
        jobsStore.updateJob(jobId, JobStatus.running, "Cloning repository", 0);
        try {
            RepositoryRecord repository = repositoryRegistry.getRepository(repoId);
            repositoryManager.cloneMirror(repository);
            RepositoryRecord readyRecord = repositoryRegistry.getRepository(repoId);
            readyRecord.status = RepositoryStatus.ready;
            readyRecord.sizeBytes = repositoryManager.calculateRepositorySize(Paths.get(readyRecord.localPath));
            readyRecord.lastFetchedAt = CoreUtils.nowIso();
            readyRecord.updatedAt = CoreUtils.nowIso();
            repositoryRegistry.updateRepository(readyRecord);
            jobsStore.finishJob(jobId, "Clone completed", new SearchResult());
        } catch (RuntimeException error) {
            cleanupFailedClone(repoId);
            jobsStore.failJob(jobId, error, "Clone failed");
            throw error;
        }
    }

    private void executeUpdate(String repoId, String jobId) {
        repositoryRegistry.updateRepositoryStatus(repoId, RepositoryStatus.updating);
        jobsStore.updateJob(jobId, JobStatus.updating_repository, "Updating repository", 0);
        try {
            RepositoryRecord repository = repositoryRegistry.getRepository(repoId);
            repositoryManager.updateMirror(repository);
            RepositoryRecord latestRecord = repositoryRegistry.getRepository(repoId);
            latestRecord.status = RepositoryStatus.ready;
            latestRecord.sizeBytes = repositoryManager.calculateRepositorySize(Paths.get(latestRecord.localPath));
            latestRecord.lastFetchedAt = CoreUtils.nowIso();
            latestRecord.updatedAt = CoreUtils.nowIso();
            repositoryRegistry.updateRepository(latestRecord);
            jobsStore.finishJob(jobId, "Update completed", new SearchResult());
        } catch (RuntimeException error) {
            handleRepositoryFailure(repoId, error);
            jobsStore.failJob(jobId, error, "Update failed");
            throw error;
        }
    }

    private void executeDelete(String repoId, String jobId) {
        RepositoryRecord repository = repositoryRegistry.getRepository(repoId);
        repository.status = RepositoryStatus.deleting;
        repository.flags.pendingDelete = true;
        repository.updatedAt = CoreUtils.nowIso();
        repositoryRegistry.updateRepository(repository);
        jobsStore.updateJob(jobId, JobStatus.running, "Deleting repository", 0);
        try {
            repositoryManager.removeMirror(repository);
            repositoryRegistry.deleteRepository(repository.id);
            jobsStore.updateJob(jobId, JobStatus.deleted, "Repository deleted", 0, new SearchResult(), null);
        } catch (RuntimeException error) {
            handleRepositoryFailure(repoId, error);
            jobsStore.failJob(jobId, error, "Delete failed");
            throw error;
        }
    }

    private void cleanupFailedClone(String repoId) {
        RepositoryRecord repository = repositoryRegistry.findRepository(repoId);
        if (repository == null) {
            return;
        }
        try {
            repositoryManager.removeMirror(repository);
        } catch (RuntimeException ignored) {
            // Keep the original clone failure in the job response.
        }
        repositoryRegistry.deleteRepository(repoId);
    }

    private void handleRepositoryFailure(String repoId, RuntimeException error) {
        RepositoryRecord repository = repositoryRegistry.findRepository(repoId);
        if (repository == null) {
            return;
        }
        repository.status = error instanceof AppException && "AUTH_FAILED".equals(((AppException) error).getCode())
                ? RepositoryStatus.auth_error
                : RepositoryStatus.broken;
        repository.updatedAt = CoreUtils.nowIso();
        repositoryRegistry.updateRepository(repository);
    }

    private void tryMarkRepositoryQueued(String repoId) {
        RepositoryRecord repository = repositoryRegistry.findRepository(repoId);
        if (repository != null && repository.status == RepositoryStatus.ready) {
            repositoryRegistry.updateRepositoryStatus(repoId, RepositoryStatus.queued);
        }
    }

    private static RepositorySummary toSummary(RepositoryRecord repository) {
        return new RepositorySummary(repository.id, repository.name, repository.status);
    }

    private static void await(java.util.concurrent.CompletableFuture<Void> completion) {
        try {
            completion.get();
        } catch (Exception error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new AppException("INTERNAL_ERROR", "Operation failed.", 500, cause.getMessage());
        }
    }

    private static NormalizedRepositoryUrl normalizeRepositoryUrl(String inputUrl, AppConfig config) {
        String trimmedInput = CoreUtils.safe(inputUrl).trim();
        if (trimmedInput.isEmpty()) {
            throw new AppException("INVALID_REPOSITORY_URL", "Repository URL is required.", 400);
        }

        URL parsedUrl;
        try {
            parsedUrl = new URL(trimmedInput);
        } catch (MalformedURLException error) {
            throw new AppException("INVALID_REPOSITORY_URL", "Repository URL is invalid.", 400);
        }

        String protocol = parsedUrl.getProtocol().toLowerCase(Locale.ROOT);
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            throw new AppException("INVALID_REPOSITORY_URL", "Only HTTP(S) repository URLs are supported.", 400);
        }

        String host = parsedUrl.getHost().toLowerCase(Locale.ROOT);
        if (parsedUrl.getPort() > 0) {
            host = host + ":" + parsedUrl.getPort();
        }
        if (config.gitLabHost != null && !config.gitLabHost.equals(host)) {
            throw new AppException("INVALID_REPOSITORY_URL", "Expected GitLab host " + config.gitLabHost + ", got " + host + ".", 400);
        }

        String pathname = parsedUrl.getPath().replaceAll("/+$", "").replaceAll("(?i)\\.git$", "");
        if (pathname.isEmpty() || "/".equals(pathname)) {
            throw new AppException("INVALID_REPOSITORY_URL", "URL must include namespace and repository name.", 400);
        }

        NormalizedRepositoryUrl result = new NormalizedRepositoryUrl();
        result.url = protocol + "://" + host + pathname + ".git";
        result.normalizedUrl = protocol + "://" + host + pathname;
        result.host = host;
        result.name = pathname.replaceAll("^/+", "");
        return result;
    }

    private static class NormalizedRepositoryUrl {
        String url;
        String normalizedUrl;
        String host;
        String name;
    }
}
