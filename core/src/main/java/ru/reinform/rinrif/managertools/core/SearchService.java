package ru.reinform.rinrif.managertools.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.reinform.rinrif.managertools.model.ApiModels.CommitSearchResult;
import ru.reinform.rinrif.managertools.model.ApiModels.FileLinkResult;
import ru.reinform.rinrif.managertools.model.ApiModels.FileSearchResult;
import ru.reinform.rinrif.managertools.model.ApiModels.JobStatus;
import ru.reinform.rinrif.managertools.model.ApiModels.JobType;
import ru.reinform.rinrif.managertools.model.ApiModels.QueuedJobResponse;
import ru.reinform.rinrif.managertools.model.ApiModels.RepositoryRecord;
import ru.reinform.rinrif.managertools.model.ApiModels.RepositoryStatus;
import ru.reinform.rinrif.managertools.model.ApiModels.SearchJobRecord;
import ru.reinform.rinrif.managertools.model.ApiModels.SearchRequestPayload;
import ru.reinform.rinrif.managertools.model.ApiModels.SearchResult;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

class SearchService {
    private final AppConfig config;
    private final RepositoryRegistry repositoryRegistry;
    private final RepositoryManager repositoryManager;
    private final RepoQueueManager queueManager;
    private final JobsStore jobsStore;
    private final GitLabUrlBuilder urlBuilder = new GitLabUrlBuilder();
    private final ObjectMapper mapper = new ObjectMapper();

    SearchService(AppConfig config, RepositoryRegistry repositoryRegistry, RepositoryManager repositoryManager, RepoQueueManager queueManager, JobsStore jobsStore) {
        this.config = config;
        this.repositoryRegistry = repositoryRegistry;
        this.repositoryManager = repositoryManager;
        this.queueManager = queueManager;
        this.jobsStore = jobsStore;
    }

    QueuedJobResponse startSearch(final SearchRequestPayload input) {
        validateSearchInput(input);
        final ExcludedFilePatterns excludedPatterns = ExcludedFilePatterns.from(input.excludedFilePatterns);
        input.excludedFilePatterns = excludedPatterns.values;
        final RepositoryRecord repository = repositoryRegistry.getRepository(input.repoId);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = mapper.convertValue(input, Map.class);
        final SearchJobRecord job = jobsStore.createJob(JobType.search, repository.id, payload, "Search queued");
        RepoQueueManager.QueueTaskHandle handle = queueManager.enqueue(repository.id, job.jobId, new RepoQueueManager.RunnableTask() {
            @Override
            public void run() {
                executeSearch(job.jobId, input);
            }
        });
        if (handle.queuePosition > 0 && repository.status == RepositoryStatus.ready) {
            repositoryRegistry.updateRepositoryStatus(repository.id, RepositoryStatus.queued);
        }
        return new QueuedJobResponse(job.jobId, handle.queuePosition);
    }

    private void executeSearch(String jobId, SearchRequestPayload input) {
        RepositoryRecord repository = repositoryRegistry.getRepository(input.repoId);
        try {
            repositoryRegistry.updateRepositoryStatus(repository.id, RepositoryStatus.updating);
            jobsStore.updateJob(jobId, JobStatus.updating_repository, "Updating repository", 0);
            repositoryManager.updateMirror(repository, input.ref);

            RepositoryRecord refreshedRepository = repositoryRegistry.getRepository(repository.id);
            refreshedRepository.lastFetchedAt = CoreUtils.nowIso();
            refreshedRepository.lastUsedAt = CoreUtils.nowIso();
            refreshedRepository.updatedAt = CoreUtils.nowIso();
            refreshedRepository.status = RepositoryStatus.searching;
            repositoryRegistry.updateRepository(refreshedRepository);

            String resolvedRef = repositoryManager.resolveRef(refreshedRepository, input.ref);
            jobsStore.updateJob(jobId, JobStatus.searching_commits, "Searching commits", 0);

            SearchQuery query = SearchQueryParser.parse(input.query);
            ExcludedFilePatterns excludedPatterns = ExcludedFilePatterns.from(input.excludedFilePatterns);
            int resultLimit = input.maxCommits == null ? config.searchDefaultMaxCommits : input.maxCommits;
            int scanLimit = Math.max(resultLimit * 20, config.searchScanLimit);
            GitLogOptions options = new GitLogOptions();
            options.resolvedRef = resolvedRef;
            options.includeMerges = Boolean.TRUE.equals(input.includeMerges);
            options.firstParent = Boolean.TRUE.equals(input.firstParent);
            options.dateFrom = input.dateFrom == null || input.dateFrom.trim().isEmpty() ? null : input.dateFrom + "T00:00:00";
            options.dateTo = input.dateTo == null || input.dateTo.trim().isEmpty() ? null : input.dateTo + "T23:59:59";
            options.scanLimit = scanLimit;

            List<CommitMatch> matches = new ArrayList<CommitMatch>();
            for (CommitRecord commit : repositoryManager.listCommits(refreshedRepository, options)) {
                CommitMatch match = CommitMatcher.match(commit, query);
                if (match != null) {
                    matches.add(CommitRanker.rank(match));
                }
            }
            Collections.sort(matches, new Comparator<CommitMatch>() {
                @Override
                public int compare(CommitMatch left, CommitMatch right) {
                    if (right.score != left.score) {
                        return right.score - left.score;
                    }
                    return CoreUtils.safe(right.commit.authoredAt).compareTo(CoreUtils.safe(left.commit.authoredAt));
                }
            });

            jobsStore.updateJob(jobId, JobStatus.building_diff_links, "Building change links", 0);
            SearchResult result = new SearchResult();
            for (CommitMatch match : matches) {
                if (result.items.size() >= resultLimit) {
                    break;
                }
                CommitSearchResult item = new CommitSearchResult();
                item.sha = match.commit.sha;
                item.subject = match.commit.subject;
                item.body = CoreUtils.emptyToNull(match.commit.body);
                item.authorName = CoreUtils.emptyToNull(match.commit.authorName);
                item.authoredAt = CoreUtils.emptyToNull(match.commit.authoredAt);
                item.score = match.score;
                item.commitUrl = urlBuilder.buildCommitUrl(refreshedRepository, match.commit.sha);
                for (FileDiffRange file : collectFilesForCommit(refreshedRepository, match.commit.sha)) {
                    if (excludedPatterns.isExcluded(file.path)) {
                        continue;
                    }
                    item.files.add(buildFileResult(refreshedRepository, match.commit.sha, file));
                }
                if (!item.files.isEmpty()) {
                    result.items.add(item);
                }
            }

            RepositoryRecord latestRepository = repositoryRegistry.getRepository(repository.id);
            latestRepository.sizeBytes = repositoryManager.calculateRepositorySize(Paths.get(latestRepository.localPath));
            latestRepository.status = RepositoryStatus.ready;
            latestRepository.updatedAt = CoreUtils.nowIso();
            latestRepository.lastUsedAt = CoreUtils.nowIso();
            repositoryRegistry.updateRepository(latestRepository);
            jobsStore.finishJob(jobId, "Search completed", result);
        } catch (RuntimeException error) {
            handleSearchFailure(repository.id, error);
            jobsStore.failJob(jobId, error, "Search failed");
            throw error;
        }
    }

    private List<FileDiffRange> collectFilesForCommit(RepositoryRecord repository, String sha) {
        try {
            List<FileDiffRange> parsed = DiffRangeParser.parse(repositoryManager.showDiff(repository, sha));
            if (!parsed.isEmpty()) {
                return parsed;
            }
        } catch (RuntimeException ignored) {
            // Fall back to file-only links below.
        }
        try {
            List<FileDiffRange> result = new ArrayList<FileDiffRange>();
            for (String path : repositoryManager.listChangedFiles(repository, sha)) {
                FileDiffRange range = new FileDiffRange(path);
                range.fallbackReason = "parse_failed";
                result.add(range);
            }
            return result;
        } catch (RuntimeException error) {
            return new ArrayList<FileDiffRange>();
        }
    }

    private FileSearchResult buildFileResult(RepositoryRecord repository, String sha, FileDiffRange file) {
        FileSearchResult result = new FileSearchResult();
        result.path = file.path;
        if (!file.ranges.isEmpty()) {
            for (LineRange range : file.ranges) {
                FileLinkResult link = new FileLinkResult();
                link.kind = "line-range";
                link.startLine = range.startLine;
                link.endLine = range.endLine;
                link.url = urlBuilder.buildLineRangeUrl(repository, sha, file.path, range.startLine, range.endLine);
                result.links.add(link);
            }
        } else {
            FileLinkResult link = new FileLinkResult();
            link.kind = "file";
            link.url = urlBuilder.buildFileUrl(repository, sha, file.path);
            link.reason = file.fallbackReason == null ? "diff_unavailable" : file.fallbackReason;
            result.links.add(link);
        }
        return result;
    }

    private void handleSearchFailure(String repoId, RuntimeException error) {
        RepositoryRecord repository = repositoryRegistry.findRepository(repoId);
        if (repository == null) {
            return;
        }
        repository.status = error instanceof AppException && "AUTH_FAILED".equals(((AppException) error).getCode())
                ? RepositoryStatus.auth_error
                : error instanceof AppException && "REF_NOT_FOUND".equals(((AppException) error).getCode())
                    ? RepositoryStatus.ready
                    : RepositoryStatus.broken;
        repository.updatedAt = CoreUtils.nowIso();
        repositoryRegistry.updateRepository(repository);
    }

    private void validateSearchInput(SearchRequestPayload input) {
        if (input == null || CoreUtils.safe(input.repoId).trim().isEmpty()) {
            throw new AppException("REPOSITORY_NOT_FOUND", "Repository id is required.", 400);
        }
        if (CoreUtils.safe(input.ref).trim().isEmpty()) {
            throw new AppException("REF_NOT_FOUND", "Ref or branch is required.", 400);
        }
        if (CoreUtils.safe(input.query).trim().isEmpty()) {
            throw new AppException("SEARCH_FAILED", "Search query is required.", 400);
        }
        if (input.query.trim().length() > config.searchMaxQueryLength) {
            throw new AppException("SEARCH_FAILED", "Search query length must not exceed " + config.searchMaxQueryLength + " characters.", 400);
        }
        if (input.maxCommits != null && (input.maxCommits < 1 || input.maxCommits > 200)) {
            throw new AppException("SEARCH_FAILED", "maxCommits must be between 1 and 200.", 400);
        }
    }
}
