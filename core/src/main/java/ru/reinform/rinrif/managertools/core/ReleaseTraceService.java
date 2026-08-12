package ru.reinform.rinrif.managertools.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.reinform.rinrif.managertools.model.ApiModels.AppError;
import ru.reinform.rinrif.managertools.model.ApiModels.JiraCommentData;
import ru.reinform.rinrif.managertools.model.ApiModels.JiraIssueData;
import ru.reinform.rinrif.managertools.model.ApiModels.JiraIssueLinkData;
import ru.reinform.rinrif.managertools.model.ApiModels.JiraRemoteLinkData;
import ru.reinform.rinrif.managertools.model.ApiModels.FileLinkResult;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceApplication;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceCommit;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceFile;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceJiraIssue;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceOverrides;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceQueuedResponse;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceReleaseCandidate;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceRepository;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceRepositoryOverride;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceRunRecord;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceRunRequest;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceSelectedRelease;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceStatus;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceTask;
import ru.reinform.rinrif.managertools.model.ApiModels.RepositoryRecord;
import ru.reinform.rinrif.managertools.model.ApiModels.RepositoryRefsResponse;
import ru.reinform.rinrif.managertools.model.ApiModels.RepositoryStatus;
import ru.reinform.rinrif.managertools.model.ApiModels.TraceConfidence;
import ru.reinform.rinrif.managertools.model.ApiModels.TraceSource;
import ru.reinform.rinrif.managertools.model.ApiModels.TraceSourceKind;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class ReleaseTraceService {
    private static final Pattern JIRA_KEY = Pattern.compile("\\b([A-Z][A-Z0-9]+-\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern APPLICATION = Pattern.compile("\\b(ui-[a-z0-9_-]+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION = Pattern.compile("\\b(\\d+\\.\\d+\\.\\d+(?:-[A-Za-z0-9._-]+)?)\\b");
    private static final Pattern PACKAGE_VERSION = Pattern.compile("\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final int COMMIT_SCAN_LIMIT = 10000;
    private static final int RANGE_LIMIT = 5000;
    private static final int RELEASE_TRACE_WORKERS = 4;
    private static final int RELEASE_TRACE_QUEUE_CAPACITY = 32;
    private static final int MAX_JIRA_INPUT_LENGTH = 2048;
    private static final int MAX_JIRA_KEY_LENGTH = 64;
    private static final int MAX_TASK_OVERRIDES = 100;
    private static final int MAX_REPOSITORY_OVERRIDES = 25;
    private static final int MAX_APPLICATION_LENGTH = 80;
    private static final int MAX_REPOSITORY_ID_LENGTH = 128;
    private static final int MAX_REF_LENGTH = 255;

    private final AppConfig config;
    private final RepositoryRegistry repositoryRegistry;
    private final RepositoryManager repositoryManager;
    private final RepoQueueManager queueManager;
    private final ReleaseTraceStore store;
    private final JiraReadClient jiraClient;
    private final GitLabReadClient gitLabClient;
    private final ThreadPoolExecutor executor;
    private final GitLabUrlBuilder urlBuilder = new GitLabUrlBuilder();

    ReleaseTraceService(AppConfig config, RepositoryRegistry repositoryRegistry, RepositoryManager repositoryManager,
                        RepoQueueManager queueManager, ReleaseTraceStore store, ObjectMapper objectMapper) {
        this(config, repositoryRegistry, repositoryManager, queueManager, store, objectMapper,
                createExecutor(RELEASE_TRACE_WORKERS, RELEASE_TRACE_QUEUE_CAPACITY));
    }

    ReleaseTraceService(AppConfig config, RepositoryRegistry repositoryRegistry, RepositoryManager repositoryManager,
                        RepoQueueManager queueManager, ReleaseTraceStore store, ObjectMapper objectMapper,
                        ThreadPoolExecutor executor) {
        this.config = config;
        this.repositoryRegistry = repositoryRegistry;
        this.repositoryManager = repositoryManager;
        this.queueManager = queueManager;
        this.store = store;
        this.jiraClient = new JiraReadClient(config.jiraBaseUrl, config.jiraUser, config.jiraToken,
                config.verifySsl, config.httpTimeoutMs, objectMapper);
        this.gitLabClient = new GitLabReadClient(config, objectMapper);
        this.executor = executor;
    }

    ReleaseTraceQueuedResponse start(final ReleaseTraceRunRequest input) {
        final ReleaseTraceRunRequest request = normalizeRequest(input);
        final ReleaseTraceRunRecord run = new ReleaseTraceRunRecord();
        run.runId = CoreUtils.createIdentifier("release");
        run.status = ReleaseTraceStatus.queued;
        run.progress = 0;
        run.step = "queued";
        run.message = "Проверка релиза поставлена в очередь";
        run.request = request;
        run.createdAt = CoreUtils.nowIso();
        run.updatedAt = run.createdAt;
        final CountDownLatch persisted = new CountDownLatch(1);
        Future<?> admitted;
        try {
            admitted = executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        persisted.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    execute(run.runId);
                }
            });
        } catch (RejectedExecutionException error) {
            throw new AppException("RELEASE_TRACE_OVERLOADED",
                    "Сервис проверки релизов занят. Повторите попытку позже.", 429);
        }
        int queuePosition = executor.getQueue().contains(admitted) ? executor.getQueue().size() : 0;
        boolean stored = false;
        try {
            store.write(run);
            stored = true;
        } finally {
            if (!stored) {
                admitted.cancel(true);
            }
            persisted.countDown();
        }
        ReleaseTraceQueuedResponse response = new ReleaseTraceQueuedResponse(run.runId);
        response.queuePosition = queuePosition;
        return response;
    }

    ReleaseTraceRunRecord get(String runId) {
        return store.get(runId);
    }

    RepositoryRefsResponse getRefs(String repoId) {
        String normalizedRepoId = normalizeSafeField(repoId, "repoId", MAX_REPOSITORY_ID_LENGTH, false);
        if (!normalizedRepoId.matches("[A-Za-z0-9_-]+")) {
            throw invalidRequest("Идентификатор репозитория имеет недопустимый формат.");
        }
        RepositoryRecord repository = repositoryRegistry.getRepository(normalizedRepoId);
        return repositoryManager.listRefs(repository);
    }

    static ThreadPoolExecutor createExecutor(int workers, int queueCapacity) {
        if (workers < 1 || queueCapacity < 0) {
            throw new IllegalArgumentException("Release trace executor limits are invalid.");
        }
        BlockingQueue<Runnable> queue = queueCapacity == 0
                ? new SynchronousQueue<Runnable>()
                : new ArrayBlockingQueue<Runnable>(queueCapacity);
        final AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS, queue,
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable,
                                "release-trace-" + sequence.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    private void execute(String runId) {
        ReleaseTraceRunRecord run = store.get(runId);
        try {
            update(run, ReleaseTraceStatus.reading_jira, 8, "reading_jira", "Читаем релизную задачу Jira");
            JiraIssueData releaseIssue = jiraClient.getIssue(run.request.jiraKey);
            run.release = toRelease(releaseIssue);
            ParsedRelease parsed = parseRelease(releaseIssue);
            normalizeRepositoryOverrideApplications(run.request.overrides);
            mergeOverrides(parsed, run.request.overrides);
            if (parsed.targets.isEmpty()) {
                throw new AppException("RELEASE_TARGETS_NOT_FOUND", "В релизной задаче не найдены приложения и задачи разработки.", 422);
            }

            update(run, ReleaseTraceStatus.resolving_targets, 18, "resolving_targets", "Определяем задачи и приложения");
            Map<String, JiraIssueData> taskIssues = readTaskIssues(parsed, run);
            List<TargetWork> work = resolveWork(parsed, run.request.overrides);
            if (work.isEmpty()) {
                throw new AppException("REPOSITORIES_NOT_FOUND", "Не найдены зарегистрированные репозитории для приложений релиза.", 422);
            }

            boolean partial = false;
            int index = 0;
            for (TargetWork target : work) {
                index++;
                ReleaseTraceApplication application = createApplication(target, taskIssues);
                run.applications.add(application);
                store.write(run);
                int baseProgress = 20 + ((index - 1) * 70 / Math.max(1, work.size()));
                try {
                    if (target.repository == null) {
                        processApplication(run, application, target, parsed, baseProgress, work.size());
                    } else {
                        final ReleaseTraceRunRecord queuedRun = run;
                        final ReleaseTraceApplication queuedApplication = application;
                        final TargetWork queuedTarget = target;
                        final int queuedProgress = baseProgress;
                        final int applicationCount = work.size();
                        await(queueManager.enqueue(target.repository.id, new RepoQueueManager.RunnableTask() {
                            @Override
                            public void run() {
                                try {
                                    processApplication(queuedRun, queuedApplication, queuedTarget, parsed,
                                            queuedProgress, applicationCount);
                                } catch (Throwable error) {
                                    markRepositoryFailure(queuedTarget.repository, error);
                                    if (error instanceof Error) {
                                        throw (Error) error;
                                    }
                                    if (error instanceof RuntimeException) {
                                        throw (RuntimeException) error;
                                    }
                                    throw new RuntimeException(error);
                                }
                            }
                        }).completion);
                    }
                    if ("partial".equals(application.status) || "failed".equals(application.status)) {
                        partial = true;
                    }
                } catch (RuntimeException error) {
                    partial = true;
                    application.status = "failed";
                    application.confidence = TraceConfidence.unconfirmed;
                    application.warnings.add(safeMessage(error, "Не удалось проверить репозиторий."));
                    run.warnings.add(application.application + ": " + safeMessage(error, "ошибка проверки репозитория"));
                    store.write(run);
                }
            }
            if (run.applications.isEmpty()) {
                throw new AppException("RELEASE_TRACE_EMPTY", "Не удалось построить состав релиза.", 422);
            }
            run.status = partial ? ReleaseTraceStatus.partial : ReleaseTraceStatus.done;
            run.progress = 100;
            run.step = partial ? "partial" : "done";
            run.message = partial ? "Состав релиза построен частично" : "Состав релиза найден";
            store.write(run);
        } catch (RuntimeException error) {
            run = store.get(runId);
            run.status = ReleaseTraceStatus.failed;
            run.progress = 100;
            run.step = "failed";
            run.message = "Не удалось построить состав релиза";
            run.error = new AppError(errorCode(error), safeMessage(error, "Ошибка построения состава релиза."), null);
            store.write(run);
        }
    }

    private void processApplication(ReleaseTraceRunRecord run, ReleaseTraceApplication application, TargetWork target,
                                    ParsedRelease parsed, int progress, int totalApps) {
        RepositoryRecord repository = target.repository;
        if (repository == null) {
            throw new AppException("REPOSITORY_NOT_FOUND", "Для приложения " + target.application + " репозиторий не зарегистрирован.", 404);
        }
        String ref = chooseRef(repository, target, parsed);
        application.ref = ref;
        update(run, ReleaseTraceStatus.updating_repositories, progress + 2, "updating_repositories",
                "Обновляем " + target.application + " / " + ref);
        repositoryRegistry.updateRepositoryStatus(repository.id, RepositoryStatus.updating);
        repositoryManager.updateMirror(repository, ref);
        RepositoryRecord refreshed = repositoryRegistry.getRepository(repository.id);
        refreshed.lastFetchedAt = CoreUtils.nowIso();
        refreshed.lastUsedAt = CoreUtils.nowIso();
        refreshed.updatedAt = CoreUtils.nowIso();
        refreshed.status = RepositoryStatus.searching;
        repositoryRegistry.updateRepository(refreshed);
        String resolvedRef = repositoryManager.resolveRef(refreshed, ref);

        update(run, ReleaseTraceStatus.finding_release, progress + 8, "finding_release",
                "Ищем выпуск " + target.application);
        List<CommitRecord> firstParent = listCommits(refreshed, resolvedRef, true, true, COMMIT_SCAN_LIMIT);
        List<CommitRecord> candidates = findMatching(firstParent, Collections.singletonList(run.release.key));
        CommitRecord selected = selectReleaseCommit(candidates, target.override == null ? null : target.override.targetSha, firstParent);
        if (selected == null) {
            application.status = "partial";
            application.confidence = TraceConfidence.unconfirmed;
            application.warnings.add("Коммит релизной задачи на ветке " + ref + " не найден.");
            addHistoricalOnly(application, refreshed, resolvedRef, target.taskKeys());
            markRepositoryReady(refreshed);
            return;
        }
        ReleaseTraceReleaseCandidate selectedCandidateDto = null;
        for (int i = 0; i < candidates.size(); i++) {
            CommitRecord candidate = candidates.get(i);
            boolean isSelected = candidate.sha.equals(selected.sha);
            ReleaseTraceReleaseCandidate dto = toCandidate(refreshed, candidate,
                    parsed.versions.get(target.application), isSelected);
            dto.selected = isSelected;
            if (isSelected) {
                selectedCandidateDto = dto;
            }
            application.releaseCandidates.add(dto);
        }
        if (candidates.size() > 1) {
            application.warnings.add("Найдено несколько выпусков с этим кодом Jira. Автоматически выбран последний.");
        }

        update(run, ReleaseTraceStatus.searching_commits, progress + 14, "searching_commits",
                "Связываем коммиты с задачами Jira");
        String releaseParentSha = repositoryManager.parentOf(refreshed, selected.sha);
        String evidenceBaseSha = previousReleaseBoundary(firstParent, selected.sha, candidates);
        List<CommitRecord> candidateRange = evidenceBaseSha == null
                ? Collections.<CommitRecord>emptyList()
                : repositoryManager.listRangeCommits(refreshed, evidenceBaseSha, selected.sha, RANGE_LIMIT);
        List<CommitRecord> taskCommits = selectLatestTaskCommits(candidateRange, target.taskKeys(), evidenceBaseSha);
        if (evidenceBaseSha == null) {
            application.warnings.add("Не найдена предыдущая граница выпуска. Старые совпадения Jira показаны только как исторические.");
        }
        List<String> missingTaskKeys = missingTaskKeys(taskCommits, target.taskKeys());
        for (String taskKey : missingTaskKeys) {
            application.warnings.add("Для задачи " + taskKey + " нет коммита с её кодом в выбранном диапазоне выпуска.");
        }
        String overrideBaseSha = target.override == null ? null : emptyToNull(target.override.baseSha);
        String taskBaseSha = taskCommits.isEmpty() ? null
                : repositoryManager.findBaseBeforeCommits(refreshed, selected.sha, shas(taskCommits));
        String baseSha = chooseBaseSha(overrideBaseSha, taskBaseSha, releaseParentSha);
        if (baseSha != null && !repositoryManager.isAncestor(refreshed, baseSha, selected.sha)) {
            application.warnings.add("Заданная нижняя граница не является предком выпуска; точный diff не построен.");
            baseSha = null;
        }

        ReleaseTraceSelectedRelease selectedDto = new ReleaseTraceSelectedRelease();
        selectedDto.sha = selected.sha;
        selectedDto.targetSha = selected.sha;
        selectedDto.baseSha = baseSha;
        selectedDto.subject = selected.subject;
        selectedDto.authoredAt = selected.authoredAt;
        selectedDto.version = selectedCandidateDto == null ? null : selectedCandidateDto.version;
        selectedDto.selected = true;
        application.selectedRelease = selectedDto;

        List<CommitRecord> exactRange = baseSha == null
                ? new ArrayList<CommitRecord>()
                : repositoryManager.listRangeCommits(refreshed, baseSha, selected.sha, RANGE_LIMIT);
        Set<String> exactShas = new HashSet<String>();
        for (CommitRecord commit : exactRange) {
            exactShas.add(commit.sha);
            application.commits.add(toCommit(refreshed, commit, target.taskKeys(), false));
        }
        List<CommitRecord> allCommits = listCommits(refreshed, resolvedRef, true, false, COMMIT_SCAN_LIMIT);
        for (CommitRecord commit : findMatching(allCommits, target.taskKeys())) {
            if (!exactShas.contains(commit.sha)) {
                application.historicalCommits.add(toCommit(refreshed, commit, target.taskKeys(), true));
            }
        }

        update(run, ReleaseTraceStatus.building_diff, progress + 20, "building_diff", "Строим итоговый diff");
        if (baseSha != null) {
            for (ReleaseTraceFile file : collectReleaseFiles(refreshed, baseSha, selected.sha)) {
                if (file.technical) {
                    application.technicalFiles.add(file);
                } else {
                    application.files.add(file);
                }
            }
            if (taskCommits.isEmpty()) {
                application.confidence = TraceConfidence.unconfirmed;
                application.status = "partial";
                application.warnings.add("Итоговый diff построен, но в выбранном диапазоне нет коммита с кодом задачи разработки.");
            } else if (!missingTaskKeys.isEmpty()) {
                application.confidence = TraceConfidence.attention;
                application.status = "partial";
            } else {
                application.confidence = TraceConfidence.confirmed;
                application.status = "done";
            }
        } else {
            application.status = "partial";
            application.confidence = TraceConfidence.commit_link;
            application.warnings.add("Связь найдена по коммитам, точный состав выпуска не подтверждён.");
        }

        update(run, ReleaseTraceStatus.finding_merge_requests, progress + 25, "finding_merge_requests", "Проверяем MR в GitLab");
        try {
            List<String> mrCommitShas = mergeRequestCommitShas(application.commits, application.historicalCommits);
            if (mrCommitShas.size() > 50) {
                application.warnings.add("Проверка MR по коммитам ограничена первыми 50 совпадениями; поиск по коду Jira выполнен полностью.");
                mrCommitShas = new ArrayList<String>(mrCommitShas.subList(0, 50));
            }
            application.mergeRequests.addAll(gitLabClient.findMergeRequests(refreshed, mrCommitShas, target.taskKeys()));
            if (application.mergeRequests.isEmpty()) {
                application.warnings.add("MR не найден. Связь подтверждается Git-коммитами и итоговым diff.");
            }
        } catch (RuntimeException error) {
            application.warnings.add("GitLab MR временно не проверен: " + safeMessage(error, "ошибка GitLab"));
        }
        markRepositoryReady(refreshed);
        store.write(run);
    }

    private Map<String, JiraIssueData> readTaskIssues(ParsedRelease parsed, ReleaseTraceRunRecord run) {
        Map<String, JiraIssueData> result = new LinkedHashMap<String, JiraIssueData>();
        for (String taskKey : parsed.allTaskKeys()) {
            try {
                result.put(taskKey, jiraClient.getIssue(taskKey));
            } catch (RuntimeException error) {
                run.warnings.add(taskKey + ": карточка Jira не прочитана, поиск по Git будет продолжен.");
            }
        }
        store.write(run);
        return result;
    }

    private List<TargetWork> resolveWork(ParsedRelease parsed, ReleaseTraceOverrides overrides) {
        List<TargetWork> result = new ArrayList<TargetWork>();
        Set<String> overriddenApplications = new HashSet<String>();
        if (overrides != null && overrides.repositories != null) {
            for (ReleaseTraceRepositoryOverride override : overrides.repositories) {
                if (override == null || CoreUtils.safe(override.repoId).trim().isEmpty()) {
                    continue;
                }
                RepositoryRecord repository = repositoryRegistry.findRepository(override.repoId);
                String app = resolveOverrideApplication(override, repository);
                ParsedTarget parsedTarget = parsed.getOrCreate(app);
                TargetWork work = new TargetWork(app, repository, parsedTarget, override);
                result.add(work);
                overriddenApplications.add(canonicalApplication(app));
            }
        }
        List<RepositoryRecord> repositories = repositoryRegistry.listRepositories();
        for (ParsedTarget target : parsed.targets.values()) {
            if (overriddenApplications.contains(canonicalApplication(target.application))) {
                continue;
            }
            RepositoryRecord repository = findRepository(repositories, target.application);
            result.add(new TargetWork(target.application, repository, target, null));
        }
        return result;
    }

    private void normalizeRepositoryOverrideApplications(ReleaseTraceOverrides overrides) {
        if (overrides == null || overrides.repositories == null) {
            return;
        }
        for (ReleaseTraceRepositoryOverride override : overrides.repositories) {
            if (override != null && CoreUtils.safe(override.application).trim().isEmpty()
                    && !CoreUtils.safe(override.repoId).trim().isEmpty()) {
                RepositoryRecord repository = repositoryRegistry.findRepository(override.repoId);
                override.application = resolveOverrideApplication(override, repository);
            } else if (override != null) {
                override.application = canonicalApplication(override.application);
            }
        }
    }

    static String resolveOverrideApplication(ReleaseTraceRepositoryOverride override, RepositoryRecord repository) {
        String repositoryApplication = repository == null ? "manual-repository" : lastSegment(repository.name);
        return canonicalApplication(firstFilled(override == null ? null : override.application, repositoryApplication));
    }

    private ReleaseTraceApplication createApplication(TargetWork work, Map<String, JiraIssueData> taskIssues) {
        ReleaseTraceApplication result = new ReleaseTraceApplication();
        result.application = work.application;
        result.status = "queued";
        if (work.repository != null) {
            result.repository = new ReleaseTraceRepository();
            result.repository.id = work.repository.id;
            result.repository.name = work.repository.name;
            result.repository.url = work.repository.normalizedUrl;
        }
        for (Map.Entry<String, List<TraceSource>> entry : work.target.taskSources.entrySet()) {
            ReleaseTraceTask task = new ReleaseTraceTask();
            task.key = entry.getKey();
            JiraIssueData issue = taskIssues.get(task.key);
            task.title = issue == null ? null : issue.title;
            task.url = issue == null ? jiraUrl(task.key) : issue.url;
            task.status = issue == null ? null : issue.status;
            task.sources.addAll(entry.getValue());
            result.tasks.add(task);
        }
        return result;
    }

    private String chooseRef(RepositoryRecord repository, TargetWork target, ParsedRelease parsed) {
        if (target.override != null && !CoreUtils.safe(target.override.ref).trim().isEmpty()) {
            return target.override.ref.trim();
        }
        List<String> versions = parsed.versions.get(target.application);
        if (versions != null) {
            for (String version : versions) {
                if (version.toLowerCase(Locale.ROOT).endsWith("-prod")) {
                    return "prod";
                }
                if (version.toLowerCase(Locale.ROOT).endsWith("-release")) {
                    return "release";
                }
            }
        }
        RepositoryRefsResponse refs = repositoryManager.listRefs(repository);
        String preferred = target.application.toLowerCase(Locale.ROOT).contains("nadzor") ? "prod" : "release";
        for (ru.reinform.rinrif.managertools.model.ApiModels.RepositoryRef ref : refs.items) {
            if ("branch".equals(ref.type) && preferred.equals(ref.name)) {
                return preferred;
            }
        }
        return refs.defaultRef;
    }

    private void addHistoricalOnly(ReleaseTraceApplication application, RepositoryRecord repository, String resolvedRef, List<String> taskKeys) {
        for (CommitRecord commit : findMatching(listCommits(repository, resolvedRef, true, false, COMMIT_SCAN_LIMIT), taskKeys)) {
            application.historicalCommits.add(toCommit(repository, commit, taskKeys, true));
        }
    }

    private List<CommitRecord> listCommits(RepositoryRecord repository, String ref, boolean includeMerges, boolean firstParent, int limit) {
        GitLogOptions options = new GitLogOptions();
        options.resolvedRef = ref;
        options.includeMerges = includeMerges;
        options.firstParent = firstParent;
        options.scanLimit = limit;
        return repositoryManager.listCommits(repository, options);
    }

    private ReleaseTraceCommit toCommit(RepositoryRecord repository, CommitRecord commit, List<String> taskKeys, boolean historical) {
        ReleaseTraceCommit result = new ReleaseTraceCommit();
        result.sha = commit.sha;
        result.shortSha = shortSha(commit.sha);
        result.subject = commit.subject;
        result.body = CoreUtils.emptyToNull(commit.body);
        result.authorName = CoreUtils.emptyToNull(commit.authorName);
        result.authoredAt = CoreUtils.emptyToNull(commit.authoredAt);
        result.url = urlBuilder.buildCommitUrl(repository, commit.sha);
        result.historical = historical;
        result.matchedTaskKeys.addAll(matchedKeys(commit, taskKeys));
        if (!result.matchedTaskKeys.isEmpty()) {
            result.source = new TraceSource(TraceSourceKind.commit_message, "Сообщение коммита", result.url,
                    join(result.matchedTaskKeys));
            result.confidence = TraceConfidence.commit_link;
        } else {
            result.source = new TraceSource(TraceSourceKind.git_ancestry, "Git ancestry", result.url, "Коммит входит в выбранный диапазон");
            result.confidence = TraceConfidence.confirmed;
        }
        return result;
    }

    private List<ReleaseTraceFile> collectReleaseFiles(RepositoryRecord repository, String baseSha, String targetSha) {
        List<FileDiffRange> parsed;
        try {
            parsed = DiffRangeParser.parse(repositoryManager.showDiff(repository, baseSha, targetSha));
        } catch (RuntimeException ignored) {
            parsed = new ArrayList<FileDiffRange>();
        }
        if (parsed.isEmpty()) {
            for (String path : repositoryManager.listChangedFiles(repository, baseSha, targetSha)) {
                FileDiffRange fallback = new FileDiffRange(path);
                fallback.fallbackReason = "parse_failed";
                parsed.add(fallback);
            }
        }
        List<ReleaseTraceFile> result = new ArrayList<ReleaseTraceFile>();
        for (FileDiffRange file : parsed) {
            result.add(toFile(repository, targetSha, file));
        }
        return result;
    }

    private ReleaseTraceFile toFile(RepositoryRecord repository, String targetSha, FileDiffRange diff) {
        ReleaseTraceFile result = new ReleaseTraceFile();
        result.path = diff.path;
        result.technical = isTechnical(diff.path);
        for (LineRange range : diff.ranges) {
            FileLinkResult link = new FileLinkResult();
            link.kind = "line-range";
            link.startLine = range.startLine;
            link.endLine = range.endLine;
            link.url = urlBuilder.buildLineRangeUrl(repository, targetSha, diff.path, range.startLine, range.endLine);
            result.links.add(link);
        }
        if (result.links.isEmpty()) {
            FileLinkResult link = new FileLinkResult();
            link.kind = "file";
            link.url = urlBuilder.buildFileUrl(repository, targetSha, diff.path);
            link.reason = diff.fallbackReason == null ? "diff_unavailable" : diff.fallbackReason;
            result.links.add(link);
        }
        result.url = result.links.get(0).url;
        result.source = new TraceSource(TraceSourceKind.final_diff, "Итоговый diff", result.url, "Файл изменён в base..target");
        result.confidence = result.technical ? TraceConfidence.technical : TraceConfidence.confirmed;
        return result;
    }

    private ReleaseTraceReleaseCandidate toCandidate(RepositoryRecord repository, CommitRecord commit,
                                                      List<String> knownVersions, boolean selected) {
        ReleaseTraceReleaseCandidate result = new ReleaseTraceReleaseCandidate();
        result.sha = commit.sha;
        result.subject = commit.subject;
        result.authoredAt = commit.authoredAt;
        result.version = resolveCandidateVersion(repository, commit, knownVersions, selected);
        return result;
    }

    private String resolveCandidateVersion(RepositoryRecord repository, CommitRecord commit,
                                           List<String> knownVersions, boolean selected) {
        return resolveVersionEvidence(
                readFileQuietly(repository, commit.sha, "ver.txt"),
                readFileQuietly(repository, commit.sha, "package.json"),
                commit.subject + "\n" + commit.body,
                knownVersions,
                selected
        );
    }

    private String readFileQuietly(RepositoryRecord repository, String sha, String filePath) {
        try {
            return repositoryManager.readFileAt(repository, sha, filePath);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String resolveVersionEvidence(String versionFile, String packageJson, String message,
                                         List<String> knownVersions, boolean selected) {
        String version = extractVersion(versionFile, null);
        if (version == null) {
            Matcher packageMatcher = PACKAGE_VERSION.matcher(CoreUtils.safe(packageJson));
            version = packageMatcher.find() ? packageMatcher.group(1) : null;
        }
        if (version == null) {
            version = extractVersion(message, null);
        }
        if (version != null) {
            return version;
        }
        return selected && knownVersions != null && !knownVersions.isEmpty()
                ? knownVersions.get(knownVersions.size() - 1)
                : null;
    }

    private ReleaseTraceJiraIssue toRelease(JiraIssueData issue) {
        ReleaseTraceJiraIssue result = new ReleaseTraceJiraIssue();
        result.key = issue.key;
        result.title = issue.title;
        result.url = issue.url;
        result.status = issue.status;
        result.releaseKeys.addAll(extractKeys(allIssueText(issue), "RLS"));
        result.sources.add(new TraceSource(TraceSourceKind.jira_description, "Описание Jira", issue.url, "Состав релиза"));
        for (JiraIssueLinkData link : issue.issueLinks) {
            result.sources.add(new TraceSource(TraceSourceKind.jira_issue_link, "Связь Jira", link.url,
                    link.relationship + ": " + link.key));
        }
        for (JiraRemoteLinkData link : issue.remoteLinks) {
            result.sources.add(new TraceSource(TraceSourceKind.jira_remote_link, "Внешняя ссылка Jira", link.url, link.title));
        }
        return result;
    }

    static ParsedRelease parseRelease(JiraIssueData issue) {
        ParsedRelease result = new ParsedRelease();
        parseText(result, issue.description, TraceSourceKind.jira_description, "Описание Jira", issue.url, issue.key);
        for (JiraCommentData comment : issue.comments) {
            String commentLabel = "Комментарий Jira" + (CoreUtils.safe(comment.author).isEmpty() ? "" : " — " + comment.author);
            parseText(result, comment.body, TraceSourceKind.jira_comment,
                    commentLabel, issue.url, issue.key);
            parseCommentVersions(result, comment.body);
            parseCommentTasks(result, comment.body, issue.key, issue.url, commentLabel);
        }
        List<String> directKeys = new ArrayList<String>();
        for (JiraIssueLinkData link : issue.issueLinks) {
            if (isDevelopmentKey(link.key, issue.key)) {
                directKeys.add(link.key.toUpperCase(Locale.ROOT));
                TraceSource source = new TraceSource(TraceSourceKind.jira_issue_link, "Связь Jira", link.url,
                        link.relationship);
                result.directSources.put(link.key.toUpperCase(Locale.ROOT), source);
            }
        }
        if (!directKeys.isEmpty()) {
            if (result.targets.isEmpty()) {
                result.getOrCreate("related-tasks");
            }
            for (ParsedTarget target : result.targets.values()) {
                for (String key : directKeys) {
                    target.addTask(key, result.directSources.get(key));
                }
            }
        }
        for (JiraRemoteLinkData link : issue.remoteLinks) {
            for (String key : extractKeys(CoreUtils.safe(link.title) + "\n" + CoreUtils.safe(link.url), null)) {
                if (isDevelopmentKey(key, issue.key)) {
                    addDirectTask(result, key, new TraceSource(TraceSourceKind.jira_remote_link,
                            "Внешняя ссылка Jira", link.url, link.title));
                }
            }
        }
        for (ParsedTarget target : result.targets.values()) {
            target.taskSources.remove(CoreUtils.safe(issue.key).toUpperCase(Locale.ROOT));
        }
        return result;
    }

    private static void parseText(ParsedRelease result, String text, TraceSourceKind kind, String label, String url, String releaseKey) {
        String currentApplication = null;
        for (String rawLine : CoreUtils.safe(text).replace("\r\n", "\n").split("\n")) {
            String line = rawLine.trim();
            Matcher applicationMatcher = APPLICATION.matcher(line);
            if (applicationMatcher.find()) {
                currentApplication = applicationMatcher.group(1).toLowerCase(Locale.ROOT);
                result.getOrCreate(currentApplication);
            }
            if (currentApplication == null) {
                continue;
            }
            ParsedTarget target = result.getOrCreate(currentApplication);
            Matcher keys = JIRA_KEY.matcher(line);
            while (keys.find()) {
                String key = keys.group(1).toUpperCase(Locale.ROOT);
                if (!key.startsWith("RLS-") && !key.equals(CoreUtils.safe(releaseKey).toUpperCase(Locale.ROOT))) {
                    target.addTask(key, new TraceSource(kind, label, url, line));
                }
            }
            Matcher versions = VERSION.matcher(line);
            while (versions.find()) {
                result.addVersion(currentApplication, versions.group(1));
            }
        }
    }

    private static void parseCommentVersions(ParsedRelease result, String text) {
        for (String rawLine : CoreUtils.safe(text).replace("\r\n", "\n").split("\n")) {
            String line = rawLine.toLowerCase(Locale.ROOT);
            List<String> versions = new ArrayList<String>();
            Matcher versionMatcher = VERSION.matcher(rawLine);
            while (versionMatcher.find()) {
                versions.add(versionMatcher.group(1));
            }
            if (versions.isEmpty()) {
                continue;
            }
            List<String> matchedApplications = new ArrayList<String>();
            for (String application : result.targets.keySet()) {
                String alias = application.startsWith("ui-") ? application.substring(3) : application;
                if (containsWord(line, application) || containsWord(line, alias)
                        || line.contains("/" + alias + "@") || line.contains("{{" + alias + "}}")) {
                    matchedApplications.add(application);
                }
            }
            if (matchedApplications.isEmpty() && result.targets.size() == 1) {
                matchedApplications.add(result.targets.keySet().iterator().next());
            }
            for (String application : matchedApplications) {
                for (String version : versions) {
                    result.addVersion(application, version);
                }
            }
        }
    }

    private static void parseCommentTasks(ParsedRelease result, String text, String releaseKey, String url, String label) {
        for (String rawLine : CoreUtils.safe(text).replace("\r\n", "\n").split("\n")) {
            List<String> developmentKeys = new ArrayList<String>();
            for (String key : extractKeys(rawLine, null)) {
                if (isDevelopmentKey(key, releaseKey)) {
                    developmentKeys.add(key);
                }
            }
            if (developmentKeys.isEmpty()) {
                continue;
            }
            List<ParsedTarget> explicitTargets = new ArrayList<ParsedTarget>();
            String loweredLine = rawLine.toLowerCase(Locale.ROOT);
            for (ParsedTarget target : result.targets.values()) {
                String alias = applicationAlias(target.application);
                if (containsWord(loweredLine, target.application) || containsWord(loweredLine, alias)
                        || loweredLine.contains("{{" + alias + "}}")) {
                    explicitTargets.add(target);
                }
            }
            for (String key : developmentKeys) {
                List<ParsedTarget> keyTargets = new ArrayList<ParsedTarget>(explicitTargets);
                if (keyTargets.isEmpty()) {
                    for (ParsedTarget target : result.targets.values()) {
                        if (target.taskSources.containsKey(key)) {
                            keyTargets.add(target);
                        }
                    }
                }
                if (keyTargets.isEmpty() && result.targets.size() == 1) {
                    keyTargets.add(result.targets.values().iterator().next());
                }
                if (keyTargets.isEmpty() && result.targets.isEmpty()) {
                    keyTargets.add(result.getOrCreate("related-tasks"));
                }
                for (ParsedTarget target : keyTargets) {
                    target.addTask(key, new TraceSource(TraceSourceKind.jira_comment, label, url, rawLine.trim()));
                }
            }
        }
    }

    private static void addDirectTask(ParsedRelease result, String key, TraceSource source) {
        if (result.targets.isEmpty()) {
            result.getOrCreate("related-tasks");
        }
        for (ParsedTarget target : result.targets.values()) {
            target.addTask(key, source);
        }
    }

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("(?<![a-z0-9_-])" + Pattern.quote(word.toLowerCase(Locale.ROOT)) + "(?![a-z0-9_-])")
                .matcher(CoreUtils.safe(text).toLowerCase(Locale.ROOT)).find();
    }

    static void mergeOverrides(ParsedRelease parsed, ReleaseTraceOverrides overrides) {
        if (overrides == null) {
            return;
        }
        if (overrides.repositories != null) {
            for (ReleaseTraceRepositoryOverride override : overrides.repositories) {
                if (override != null && !CoreUtils.safe(override.application).trim().isEmpty()) {
                    parsed.getOrCreate(canonicalApplication(override.application));
                }
            }
        }
        if (overrides.taskKeys != null) {
            if (parsed.targets.isEmpty() && !overrides.taskKeys.isEmpty()) {
                parsed.getOrCreate("manual");
            }
            for (ParsedTarget target : parsed.targets.values()) {
                for (String inputKey : overrides.taskKeys) {
                    String key = normalizeTaskKey(inputKey);
                    if (key != null) {
                        target.addTask(key, new TraceSource(TraceSourceKind.manual_override, "Ручная настройка", null, key));
                    }
                }
            }
        }
    }

    static ReleaseTraceRunRequest normalizeRequest(ReleaseTraceRunRequest input) {
        if (input == null) {
            throw new AppException("INVALID_JIRA_KEY", "Код или ссылка Jira обязательны.", 400);
        }
        validateOptionalLength(input.jira, "jira", MAX_JIRA_INPUT_LENGTH);
        validateOptionalLength(input.jiraKey, "jiraKey", MAX_JIRA_KEY_LENGTH);
        String source = firstFilled(input.jiraKey, input.jira);
        Matcher matcher = JIRA_KEY.matcher(CoreUtils.safe(source).toUpperCase(Locale.ROOT));
        if (!matcher.find()) {
            throw new AppException("INVALID_JIRA_KEY", "Не удалось определить код Jira.", 400);
        }
        ReleaseTraceRunRequest result = new ReleaseTraceRunRequest();
        result.jiraKey = matcher.group(1).toUpperCase(Locale.ROOT);
        result.jira = result.jiraKey;
        if (result.jiraKey.startsWith("RLS-")) {
            throw new AppException("INVALID_JIRA_KEY", "Укажите релизную задачу подготовки ветки, а не номер RLS.", 400);
        }
        result.overrides = normalizeOverrides(input.overrides);
        return result;
    }

    private static ReleaseTraceOverrides normalizeOverrides(ReleaseTraceOverrides input) {
        ReleaseTraceOverrides result = new ReleaseTraceOverrides();
        if (input == null) {
            return result;
        }
        List<String> inputTaskKeys = input.taskKeys == null
                ? Collections.<String>emptyList() : input.taskKeys;
        if (inputTaskKeys.size() > MAX_TASK_OVERRIDES) {
            throw invalidRequest("Можно указать не более " + MAX_TASK_OVERRIDES + " задач вручную.");
        }
        LinkedHashSet<String> taskKeys = new LinkedHashSet<String>();
        for (String inputKey : inputTaskKeys) {
            validateOptionalLength(inputKey, "taskKey", MAX_JIRA_KEY_LENGTH);
            String key = normalizeTaskKeyExact(inputKey);
            if (key == null) {
                throw invalidRequest("Код задачи в ручных настройках имеет недопустимый формат.");
            }
            taskKeys.add(key);
        }
        result.taskKeys.addAll(taskKeys);

        List<ReleaseTraceRepositoryOverride> inputRepositories = input.repositories == null
                ? Collections.<ReleaseTraceRepositoryOverride>emptyList() : input.repositories;
        if (inputRepositories.size() > MAX_REPOSITORY_OVERRIDES) {
            throw invalidRequest("Можно указать не более " + MAX_REPOSITORY_OVERRIDES + " репозиториев вручную.");
        }
        for (ReleaseTraceRepositoryOverride inputRepository : inputRepositories) {
            if (inputRepository == null) {
                continue;
            }
            ReleaseTraceRepositoryOverride repository = new ReleaseTraceRepositoryOverride();
            String application = normalizeSafeField(inputRepository.application, "application",
                    MAX_APPLICATION_LENGTH, true);
            if (application != null) {
                if (!application.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
                    throw invalidRequest("Название приложения имеет недопустимый формат.");
                }
                repository.application = canonicalApplication(application);
            }
            repository.repoId = normalizeSafeField(inputRepository.repoId, "repoId",
                    MAX_REPOSITORY_ID_LENGTH, true);
            if (repository.repoId != null && !repository.repoId.matches("[A-Za-z0-9_-]+")) {
                throw invalidRequest("Идентификатор репозитория имеет недопустимый формат.");
            }
            repository.ref = normalizeRef(inputRepository.ref);
            repository.baseSha = normalizeSha(inputRepository.baseSha, "baseSha");
            repository.targetSha = normalizeSha(inputRepository.targetSha, "targetSha");
            result.repositories.add(repository);
        }
        return result;
    }

    private static String normalizeTaskKeyExact(String input) {
        String value = CoreUtils.safe(input).trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z][A-Z0-9]+-\\d+") || value.startsWith("RLS-")) {
            return null;
        }
        return value;
    }

    private static String normalizeRef(String input) {
        String value = normalizeSafeField(input, "ref", MAX_REF_LENGTH, true);
        if (value == null) {
            return null;
        }
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._/-]*") || value.contains("..")
                || value.contains("@{") || value.endsWith("/") || value.endsWith(".")) {
            throw invalidRequest("Ветка или ref имеет недопустимый формат.");
        }
        return value;
    }

    private static String normalizeSha(String input, String fieldName) {
        String value = normalizeSafeField(input, fieldName, 64, true);
        if (value == null) {
            return null;
        }
        if (!value.matches("[0-9a-fA-F]{7,64}")) {
            throw invalidRequest(fieldName + " имеет недопустимый формат SHA.");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String normalizeSafeField(String input, String fieldName, int maxLength, boolean optional) {
        if (input == null || input.trim().isEmpty()) {
            if (optional) {
                return null;
            }
            throw invalidRequest("Поле " + fieldName + " обязательно.");
        }
        String value = input.trim();
        if (value.length() > maxLength || containsControlCharacter(value)) {
            throw invalidRequest("Поле " + fieldName + " имеет недопустимую длину или формат.");
        }
        return value;
    }

    private static void validateOptionalLength(String value, String fieldName, int maxLength) {
        if (value != null && (value.length() > maxLength || containsControlCharacter(value))) {
            throw invalidRequest("Поле " + fieldName + " имеет недопустимую длину или формат.");
        }
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static AppException invalidRequest(String message) {
        return new AppException("RELEASE_TRACE_INVALID_REQUEST", message, 400);
    }

    private static List<CommitRecord> findMatching(List<CommitRecord> commits, List<String> taskKeys) {
        List<CommitRecord> result = new ArrayList<CommitRecord>();
        for (CommitRecord commit : commits) {
            if (!matchedKeys(commit, taskKeys).isEmpty()) {
                result.add(commit);
            }
        }
        return result;
    }

    /**
     * The range is ordered newest-first by GitLogService. One task can be mentioned in a
     * sequence of fixes or merges; only its newest occurrence defines this release slice.
     */
    static List<CommitRecord> selectLatestTaskCommits(List<CommitRecord> commits, List<String> taskKeys) {
        Map<String, CommitRecord> latestByTask = new LinkedHashMap<String, CommitRecord>();
        for (CommitRecord commit : commits) {
            for (String key : matchedKeys(commit, taskKeys)) {
                if (!latestByTask.containsKey(key)) {
                    latestByTask.put(key, commit);
                }
            }
            if (latestByTask.size() == taskKeys.size()) {
                break;
            }
        }
        LinkedHashMap<String, CommitRecord> uniqueBySha = new LinkedHashMap<String, CommitRecord>();
        for (CommitRecord commit : latestByTask.values()) {
            uniqueBySha.put(commit.sha, commit);
        }
        return new ArrayList<CommitRecord>(uniqueBySha.values());
    }

    static List<CommitRecord> selectLatestTaskCommits(List<CommitRecord> commits, List<String> taskKeys,
                                                       String releaseBoundarySha) {
        if (CoreUtils.safe(releaseBoundarySha).trim().isEmpty()) {
            return Collections.emptyList();
        }
        return selectLatestTaskCommits(commits, taskKeys);
    }

    private static List<String> matchedKeys(CommitRecord commit, List<String> taskKeys) {
        List<String> result = new ArrayList<String>();
        String message = (CoreUtils.safe(commit.subject) + "\n" + CoreUtils.safe(commit.body)).toUpperCase(Locale.ROOT);
        for (String key : taskKeys) {
            if (Pattern.compile("(?<![A-Z0-9])" + Pattern.quote(key.toUpperCase(Locale.ROOT)) + "(?![A-Z0-9])").matcher(message).find()) {
                result.add(key);
            }
        }
        return result;
    }

    private static List<String> missingTaskKeys(List<CommitRecord> commits, List<String> taskKeys) {
        Set<String> matched = new HashSet<String>();
        for (CommitRecord commit : commits) {
            matched.addAll(matchedKeys(commit, taskKeys));
        }
        List<String> result = new ArrayList<String>();
        for (String taskKey : taskKeys) {
            if (!matched.contains(taskKey)) {
                result.add(taskKey);
            }
        }
        return result;
    }

    static CommitRecord selectReleaseCommit(List<CommitRecord> candidates, String targetOverride, List<CommitRecord> firstParent) {
        if (!CoreUtils.safe(targetOverride).trim().isEmpty()) {
            String requested = targetOverride.trim();
            for (CommitRecord commit : firstParent) {
                if (commit.sha.equals(requested) || commit.sha.startsWith(requested)) {
                    return commit;
                }
            }
            throw new AppException("RELEASE_COMMIT_NOT_FOUND", "Указанный target SHA не найден на выбранной ветке.", 422);
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    static RepositoryRecord findRepository(List<RepositoryRecord> repositories, String application) {
        String expected = applicationAlias(application);
        RepositoryRecord partial = null;
        for (RepositoryRecord repository : repositories) {
            String name = CoreUtils.safe(repository.name).toLowerCase(Locale.ROOT).replaceAll("(?i)\\.git$", "");
            String last = applicationAlias(lastSegment(name));
            if (last.equals(expected)) {
                return repository;
            }
            if (name.matches(".*/(?:ui-)?" + Pattern.quote(expected) + "$")) {
                partial = repository;
            }
        }
        return partial;
    }

    private void update(ReleaseTraceRunRecord run, ReleaseTraceStatus status, int progress, String step, String message) {
        run.status = status;
        run.progress = Math.max(run.progress, Math.min(progress, 99));
        run.step = step;
        run.message = message;
        store.write(run);
    }

    private void markRepositoryReady(RepositoryRecord input) {
        if (input == null) {
            return;
        }
        RepositoryRecord repository = repositoryRegistry.findRepository(input.id);
        if (repository != null) {
            repository.status = RepositoryStatus.ready;
            repository.lastUsedAt = CoreUtils.nowIso();
            repository.updatedAt = CoreUtils.nowIso();
            try {
                repository.sizeBytes = repositoryManager.calculateRepositorySize(Paths.get(repository.localPath));
            } catch (RuntimeException ignored) {
                // Size is optional and must not invalidate a read-only trace.
            }
            repositoryRegistry.updateRepository(repository);
        }
    }

    private void markRepositoryFailure(RepositoryRecord input, Throwable error) {
        if (input == null) {
            return;
        }
        RepositoryRecord repository = repositoryRegistry.findRepository(input.id);
        if (repository == null) {
            return;
        }
        if (error instanceof AppException && "AUTH_FAILED".equals(((AppException) error).getCode())) {
            repository.status = RepositoryStatus.auth_error;
        } else if (error instanceof AppException && "REF_NOT_FOUND".equals(((AppException) error).getCode())) {
            repository.status = RepositoryStatus.ready;
        } else {
            repository.status = RepositoryStatus.broken;
        }
        repository.updatedAt = CoreUtils.nowIso();
        repositoryRegistry.updateRepository(repository);
    }

    private String jiraUrl(String key) {
        return config.jiraBaseUrl + "/browse/" + key;
    }

    static boolean isTechnical(String path) {
        String name = CoreUtils.safe(path).toLowerCase(Locale.ROOT).replace('\\', '/');
        String fileName = lastSegment(name);
        return "package.json".equals(fileName)
                || "package-lock.json".equals(fileName)
                || "npm-shrinkwrap.json".equals(fileName)
                || "yarn.lock".equals(fileName)
                || "pnpm-lock.yaml".equals(fileName)
                || "ver.txt".equals(fileName)
                || ".npmrc".equals(fileName)
                || fileName.endsWith(".lock")
                || name.startsWith("dist/")
                || name.contains("/dist/")
                || name.startsWith("build/")
                || name.contains("/build/");
    }

    static String previousReleaseBoundary(List<CommitRecord> firstParent, String selectedSha) {
        return previousReleaseBoundary(firstParent, selectedSha, Collections.<CommitRecord>emptyList());
    }

    static String previousReleaseBoundary(List<CommitRecord> firstParent, String selectedSha,
                                          List<CommitRecord> releaseCandidates) {
        Set<String> candidateShas = new HashSet<String>();
        for (CommitRecord candidate : releaseCandidates) {
            if (!candidate.sha.equals(selectedSha)) {
                candidateShas.add(candidate.sha);
            }
        }
        boolean selectedSeen = false;
        for (CommitRecord commit : firstParent) {
            if (!selectedSeen) {
                selectedSeen = commit.sha.equals(selectedSha);
                continue;
            }
            if (candidateShas.contains(commit.sha) || isReleaseBoundary(commit)) {
                return commit.sha;
            }
        }
        return null;
    }

    static boolean isReleaseBoundary(CommitRecord commit) {
        String message = (CoreUtils.safe(commit.subject) + "\n" + CoreUtils.safe(commit.body))
                .toLowerCase(Locale.ROOT);
        if (message.matches("(?s).*\\bver\\.?\\s*incr(?:ement)?\\b.*")) {
            return true;
        }
        if ((message.contains("подготов") && message.contains("релизн"))
                || message.matches("(?s).*\\brls-\\d+\\b.*")
                || message.matches("(?s).*\\brelease[-_ ]?prep(?:aration)?\\b.*")) {
            return true;
        }
        boolean componentUpdate = message.contains("компонент") || message.contains("component")
                || message.contains("зависимост") || message.contains("dependency")
                || message.contains("dependencies") || message.contains("библиотек")
                || message.contains("library");
        if (componentUpdate && !message.contains("release") && !message.contains("prod")) {
            return false;
        }
        boolean versionContext = message.contains("version") || message.contains("верси")
                || message.contains("release") || message.contains("prod") || message.contains("сборк");
        boolean bump = message.contains("bump") || message.contains("increment") || message.contains("incr")
                || message.contains("повыш") || message.contains("увелич") || message.contains("поднят");
        if (versionContext && bump) {
            return true;
        }
        Matcher version = VERSION.matcher(message);
        return version.find() && versionContext;
    }

    static String chooseBaseSha(String overrideBaseSha, String taskBaseSha, String releaseParentSha) {
        if (!CoreUtils.safe(overrideBaseSha).trim().isEmpty()) {
            return overrideBaseSha;
        }
        if (!CoreUtils.safe(taskBaseSha).trim().isEmpty()) {
            return taskBaseSha;
        }
        return emptyToNull(releaseParentSha);
    }

    private static String extractVersion(String text, List<String> knownVersions) {
        Matcher matcher = VERSION.matcher(CoreUtils.safe(text));
        if (matcher.find()) {
            return matcher.group(1);
        }
        return knownVersions == null || knownVersions.isEmpty() ? null : knownVersions.get(knownVersions.size() - 1);
    }

    private static String allIssueText(JiraIssueData issue) {
        StringBuilder text = new StringBuilder(CoreUtils.safe(issue.title)).append('\n').append(CoreUtils.safe(issue.description));
        for (JiraCommentData comment : issue.comments) {
            text.append('\n').append(CoreUtils.safe(comment.body));
        }
        for (JiraIssueLinkData link : issue.issueLinks) {
            text.append('\n').append(CoreUtils.safe(link.key));
        }
        return text.toString();
    }

    private static List<String> extractKeys(String text, String prefix) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        Matcher matcher = JIRA_KEY.matcher(CoreUtils.safe(text));
        while (matcher.find()) {
            String key = matcher.group(1).toUpperCase(Locale.ROOT);
            if (prefix == null || key.startsWith(prefix + "-")) {
                result.add(key);
            }
        }
        return new ArrayList<String>(result);
    }

    private static boolean isDevelopmentKey(String key, String releaseKey) {
        String normalized = CoreUtils.safe(key).toUpperCase(Locale.ROOT);
        return !normalized.isEmpty() && !normalized.startsWith("RLS-") && !normalized.equals(CoreUtils.safe(releaseKey).toUpperCase(Locale.ROOT));
    }

    private static String normalizeTaskKey(String input) {
        Matcher matcher = JIRA_KEY.matcher(CoreUtils.safe(input).toUpperCase(Locale.ROOT));
        if (!matcher.find()) {
            return null;
        }
        String key = matcher.group(1).toUpperCase(Locale.ROOT);
        return key.startsWith("RLS-") ? null : key;
    }

    private static List<String> shas(List<CommitRecord> commits) {
        List<String> result = new ArrayList<String>();
        for (CommitRecord commit : commits) {
            result.add(commit.sha);
        }
        return result;
    }

    static List<String> mergeRequestCommitShas(List<ReleaseTraceCommit> exact, List<ReleaseTraceCommit> historical) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        for (ReleaseTraceCommit commit : exact) {
            if (!commit.matchedTaskKeys.isEmpty()) {
                result.add(commit.sha);
            }
        }
        for (ReleaseTraceCommit commit : historical) {
            if (!commit.matchedTaskKeys.isEmpty()) {
                result.add(commit.sha);
            }
        }
        return new ArrayList<String>(result);
    }

    private static String safeMessage(RuntimeException error, String fallback) {
        if (error instanceof AppException) {
            String message = ((AppException) error).getMessage();
            return CoreUtils.safe(message).trim().isEmpty() ? fallback : message;
        }
        return fallback;
    }

    private static String errorCode(RuntimeException error) {
        return error instanceof AppException ? ((AppException) error).getCode() : "INTERNAL_ERROR";
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() <= 8 ? CoreUtils.safe(sha) : sha.substring(0, 8);
    }

    private static String lastSegment(String value) {
        String normalized = CoreUtils.safe(value).replace('\\', '/').replaceAll("/+$", "");
        int separator = normalized.lastIndexOf('/');
        return separator < 0 ? normalized : normalized.substring(separator + 1);
    }

    private static String canonicalApplication(String value) {
        String normalized = CoreUtils.safe(value).trim().toLowerCase(Locale.ROOT);
        String alias = applicationAlias(normalized);
        if (alias.isEmpty() || "related-tasks".equals(alias) || "manual".equals(alias)
                || "manual-repository".equals(alias)) {
            return alias;
        }
        return normalized.startsWith("ui-") ? normalized : "ui-" + alias;
    }

    private static String applicationAlias(String value) {
        String normalized = CoreUtils.safe(value).trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("ui-") ? normalized.substring(3) : normalized;
    }

    private static String firstFilled(String first, String second) {
        return first != null && !first.trim().isEmpty() ? first.trim() : CoreUtils.safe(second).trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append(", ");
            }
            result.append(value);
        }
        return result.toString();
    }

    private static void await(java.util.concurrent.CompletableFuture<Void> completion) {
        try {
            completion.get();
        } catch (Exception error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new AppException("INTERNAL_ERROR", "Repository operation failed.", 500);
        }
    }

    static class ParsedRelease {
        final Map<String, ParsedTarget> targets = new LinkedHashMap<String, ParsedTarget>();
        final Map<String, List<String>> versions = new LinkedHashMap<String, List<String>>();
        final Map<String, TraceSource> directSources = new LinkedHashMap<String, TraceSource>();

        ParsedTarget getOrCreate(String application) {
            String normalized = CoreUtils.safe(application).trim().toLowerCase(Locale.ROOT);
            ParsedTarget existing = targets.get(normalized);
            if (existing != null) {
                return existing;
            }
            ParsedTarget created = new ParsedTarget(normalized);
            targets.put(normalized, created);
            return created;
        }

        void addVersion(String application, String version) {
            List<String> values = versions.get(application);
            if (values == null) {
                values = new ArrayList<String>();
                versions.put(application, values);
            }
            if (!values.contains(version)) {
                values.add(version);
            }
        }

        List<String> allTaskKeys() {
            LinkedHashSet<String> result = new LinkedHashSet<String>();
            for (ParsedTarget target : targets.values()) {
                result.addAll(target.taskSources.keySet());
            }
            return new ArrayList<String>(result);
        }
    }

    static class ParsedTarget {
        final String application;
        final Map<String, List<TraceSource>> taskSources = new LinkedHashMap<String, List<TraceSource>>();

        ParsedTarget(String application) {
            this.application = application;
        }

        void addTask(String key, TraceSource source) {
            List<TraceSource> sources = taskSources.get(key);
            if (sources == null) {
                sources = new ArrayList<TraceSource>();
                taskSources.put(key, sources);
            }
            sources.add(source);
        }
    }

    private static class TargetWork {
        final String application;
        final RepositoryRecord repository;
        final ParsedTarget target;
        final ReleaseTraceRepositoryOverride override;

        TargetWork(String application, RepositoryRecord repository, ParsedTarget target, ReleaseTraceRepositoryOverride override) {
            this.application = application;
            this.repository = repository;
            this.target = target;
            this.override = override;
        }

        List<String> taskKeys() {
            return new ArrayList<String>(target.taskSources.keySet());
        }
    }
}
