package ru.reinform.rinrif.managertools.core;

import org.junit.Assert;
import org.junit.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.reinform.rinrif.managertools.model.ApiModels.JiraCommentData;
import ru.reinform.rinrif.managertools.model.ApiModels.JiraIssueData;
import ru.reinform.rinrif.managertools.model.ApiModels.JiraIssueLinkData;
import ru.reinform.rinrif.managertools.model.ApiModels.JiraRemoteLinkData;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceOverrides;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceRepositoryOverride;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceCommit;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceRunRequest;
import ru.reinform.rinrif.managertools.model.ApiModels.RepositoryRecord;
import ru.reinform.rinrif.managertools.model.ApiModels.TraceSource;
import ru.reinform.rinrif.managertools.model.ApiModels.TraceSourceKind;

import java.util.Arrays;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ReleaseTraceServiceTest {
    @Test
    public void canonicalizesRequestWithoutPersistingOriginalUrlOrSharedOverrides() throws Exception {
        ReleaseTraceRunRequest input = new ReleaseTraceRunRequest();
        input.jira = "https://jira.example/browse/MGSNSMART-18443?access_token=super-secret";
        input.overrides = new ReleaseTraceOverrides();
        input.overrides.taskKeys.add("mgsnsmart-18233");
        ReleaseTraceRepositoryOverride repository = new ReleaseTraceRepositoryOverride();
        repository.application = "NADZOR";
        repository.repoId = "repo_safe-1";
        repository.ref = "prod";
        repository.baseSha = "ABCDEF1";
        input.overrides.repositories.add(repository);

        ReleaseTraceRunRequest normalized = ReleaseTraceService.normalizeRequest(input);

        Assert.assertEquals("MGSNSMART-18443", normalized.jira);
        Assert.assertEquals("MGSNSMART-18443", normalized.jiraKey);
        Assert.assertEquals(Arrays.asList("MGSNSMART-18233"), normalized.overrides.taskKeys);
        Assert.assertEquals("ui-nadzor", normalized.overrides.repositories.get(0).application);
        Assert.assertEquals("abcdef1", normalized.overrides.repositories.get(0).baseSha);
        Assert.assertNotSame(input.overrides, normalized.overrides);
        input.overrides.taskKeys.set(0, "MGSNSMART-99999");
        repository.repoId = "C:\\private\\mirror";
        Assert.assertEquals("MGSNSMART-18233", normalized.overrides.taskKeys.get(0));
        Assert.assertEquals("repo_safe-1", normalized.overrides.repositories.get(0).repoId);
        Assert.assertFalse(new ObjectMapper().writeValueAsString(normalized).contains("super-secret"));
    }

    @Test
    public void rejectsOversizedOrUnsafeReleaseTraceInputs() {
        ReleaseTraceRunRequest oversizedJira = new ReleaseTraceRunRequest();
        oversizedJira.jira = repeat('x', 2049) + "MGSNSMART-18443";
        assertInvalidRequest(oversizedJira);

        ReleaseTraceRunRequest tooManyTasks = request("MGSNSMART-18443");
        for (int index = 0; index < 101; index++) {
            tooManyTasks.overrides.taskKeys.add("MGSNSMART-" + (10000 + index));
        }
        assertInvalidRequest(tooManyTasks);

        ReleaseTraceRunRequest unsafeRepository = request("MGSNSMART-18443");
        ReleaseTraceRepositoryOverride repository = new ReleaseTraceRepositoryOverride();
        repository.repoId = "C:\\private\\mirror";
        unsafeRepository.overrides.repositories.add(repository);
        assertInvalidRequest(unsafeRepository);
    }

    @Test
    public void overloadRejectsBeforePersistingQueuedRun() throws Exception {
        Path storage = Files.createTempDirectory("release-trace-overload-");
        ObjectMapper mapper = new ObjectMapper();
        ThreadPoolExecutor executor = ReleaseTraceService.createExecutor(1, 1);
        final CountDownLatch active = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        Runnable blocker = new Runnable() {
            @Override
            public void run() {
                active.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        executor.execute(blocker);
        Assert.assertTrue(active.await(2, TimeUnit.SECONDS));
        executor.execute(blocker);
        AppConfig config = new AppConfig(storage, 1000L, 512, 30, 1000,
                null, null, "https://jira.example", null, null, true, 1000);
        ReleaseTraceStore store = new ReleaseTraceStore(storage, mapper);
        ReleaseTraceService service = new ReleaseTraceService(
                config, null, null, null, store, mapper, executor);
        try {
            service.start(request("MGSNSMART-18443"));
            Assert.fail("Saturated admission queue must reject the request");
        } catch (AppException expected) {
            Assert.assertEquals("RELEASE_TRACE_OVERLOADED", expected.getCode());
            Assert.assertEquals(429, expected.getStatusCode());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        Path runs = storage.resolve("meta").resolve("release-trace").resolve("runs");
        Assert.assertFalse(Files.exists(runs));
    }

    @Test
    public void refsAndUnexpectedErrorsDoNotExposeInternalDetails() {
        AppException safe = RepositoryManager.safeRefsFailure(
                new AppException("REFS_FAILED", "remote", 500, "https://token@host/repo"),
                new AppException("BROKEN_REPOSITORY", "local", 409, "C:\\private\\mirror"));
        Assert.assertEquals("REFS_UNAVAILABLE", safe.getCode());
        Assert.assertEquals(503, safe.getStatusCode());
        Assert.assertNull(safe.toAppError().details);
        Assert.assertFalse(safe.getMessage().contains("private"));
        Assert.assertNull(AppException.toAppError(
                new RuntimeException("C:\\private\\mirror")).details);
    }

    @Test
    public void parsesApplicationsTasksVersionsAndProvenanceWithoutReleaseOrRlsKeys() {
        JiraIssueData issue = new JiraIssueData();
        issue.key = "MGSNSMART-18443";
        issue.url = "https://jira.example/browse/MGSNSMART-18443";
        issue.description = "*ui-nadzor*\nMGSNSMART-18233 MGSNSMART-18443 RLS-3322\n"
                + "*ui-violation*\nMGSNSMART-18233";
        JiraCommentData nadzor = new JiraCommentData();
        nadzor.author = "Manager";
        nadzor.body = "Для приложения {{nadzor}}, ветка prod\n@reinform-rinrif/nadzor@5.0.1365-prod\n"
                + "nadzor MGSNSMART-99999";
        issue.comments.add(nadzor);
        JiraCommentData violation = new JiraCommentData();
        violation.body = "violation, ветка release, версия 5.0.42-release";
        issue.comments.add(violation);
        JiraIssueLinkData rls = link("RLS-3322", "релиз");
        JiraIssueLinkData direct = link("MGSNSMART-18000", "включает");
        issue.issueLinks.add(rls);
        issue.issueLinks.add(direct);
        JiraRemoteLinkData remote = new JiraRemoteLinkData();
        remote.title = "MR for MGSNSMART-18111";
        remote.url = "https://gitlab.example/mr/1";
        issue.remoteLinks.add(remote);

        ReleaseTraceService.ParsedRelease parsed = ReleaseTraceService.parseRelease(issue);

        Assert.assertEquals(2, parsed.targets.size());
        Assert.assertTrue(parsed.targets.get("ui-nadzor").taskSources.containsKey("MGSNSMART-18233"));
        Assert.assertTrue(parsed.targets.get("ui-violation").taskSources.containsKey("MGSNSMART-18233"));
        Assert.assertTrue(parsed.targets.get("ui-nadzor").taskSources.containsKey("MGSNSMART-18000"));
        Assert.assertFalse(parsed.targets.get("ui-nadzor").taskSources.containsKey("MGSNSMART-18443"));
        Assert.assertFalse(parsed.targets.get("ui-nadzor").taskSources.containsKey("RLS-3322"));
        Assert.assertTrue(parsed.targets.get("ui-nadzor").taskSources.containsKey("MGSNSMART-99999"));
        Assert.assertFalse(parsed.targets.get("ui-violation").taskSources.containsKey("MGSNSMART-99999"));
        Assert.assertTrue(parsed.targets.get("ui-nadzor").taskSources.containsKey("MGSNSMART-18111"));
        Assert.assertEquals(TraceSourceKind.jira_remote_link,
                parsed.targets.get("ui-nadzor").taskSources.get("MGSNSMART-18111").get(0).kind);
        Assert.assertEquals(Arrays.asList("5.0.1365-prod"), parsed.versions.get("ui-nadzor"));
        Assert.assertEquals(Arrays.asList("5.0.42-release"), parsed.versions.get("ui-violation"));
        List<TraceSource> descriptionSources = parsed.targets.get("ui-nadzor").taskSources.get("MGSNSMART-18233");
        Assert.assertEquals(TraceSourceKind.jira_description, descriptionSources.get(0).kind);
        Assert.assertEquals(TraceSourceKind.jira_issue_link,
                parsed.targets.get("ui-nadzor").taskSources.get("MGSNSMART-18000").get(0).kind);
    }

    @Test
    public void selectsLatestCandidateAndHonorsTargetOverride() {
        CommitRecord latest = commit("aaaaaaaa", "MGSNSMART-1 release");
        CommitRecord older = commit("bbbbbbbb", "MGSNSMART-1 release");
        List<CommitRecord> candidates = Arrays.asList(latest, older);
        Assert.assertSame(latest, ReleaseTraceService.selectReleaseCommit(candidates, null, candidates));
        Assert.assertSame(older, ReleaseTraceService.selectReleaseCommit(candidates, "bbbb", candidates));
    }

    @Test
    public void selectsOnlyNewestCommitForEachTaskKey() {
        CommitRecord newestFirstTask = commit("new-task-1", "MGSNSMART-1 final merge");
        CommitRecord newestSecondTask = commit("new-task-2", "MGSNSMART-2 final change");
        CommitRecord olderBothTasks = commit("old-both", "MGSNSMART-1 MGSNSMART-2 earlier work");

        List<CommitRecord> selected = ReleaseTraceService.selectLatestTaskCommits(
                Arrays.asList(newestFirstTask, newestSecondTask, olderBothTasks),
                Arrays.asList("MGSNSMART-1", "MGSNSMART-2"));

        Assert.assertEquals(Arrays.asList(newestFirstTask, newestSecondTask), selected);
    }

    @Test
    public void recognizesReleaseBoundaryMessagesWithoutNumericVersion() {
        Assert.assertTrue(ReleaseTraceService.isReleaseBoundary(commit("a", "ver incr.")));
        Assert.assertTrue(ReleaseTraceService.isReleaseBoundary(commit("b", "version bump")));
        Assert.assertTrue(ReleaseTraceService.isReleaseBoundary(commit("c", "prod increment")));
        Assert.assertTrue(ReleaseTraceService.isReleaseBoundary(commit("d", "RELEASE. Повышение версии до 5.0.42-release")));
        Assert.assertTrue(ReleaseTraceService.isReleaseBoundary(commit("e", "MGSNSMART-1 Подготовить релизную ветку")));
        Assert.assertTrue(ReleaseTraceService.isReleaseBoundary(commit("f", "release-prep")));
        Assert.assertTrue(ReleaseTraceService.isReleaseBoundary(commit("g", "Вывод RLS-3322")));
        Assert.assertFalse(ReleaseTraceService.isReleaseBoundary(commit("h", "Merge branch 'MGSNSMART-1' into release")));
        Assert.assertFalse(ReleaseTraceService.isReleaseBoundary(commit("i", "Обычное изменение формы")));
        Assert.assertFalse(ReleaseTraceService.isReleaseBoundary(commit("j", "Обновить версию компонентов до 5.0.643")));
        Assert.assertFalse(ReleaseTraceService.isReleaseBoundary(commit("k", "Bump dependency version to 5.0.643")));
    }

    @Test
    public void choosesNearestGenericBoundaryBeforeOlderSameReleaseCandidate() {
        CommitRecord selected = commit("selected", "MGSNSMART-18443 release");
        CommitRecord featureMerge = commit("merge", "Merge branch 'MGSNSMART-18233' into release");
        CommitRecord nearestBoundary = commit("nearest", "MGSNSMART-18721 Подготовить релизную ветку");
        CommitRecord olderSameRelease = commit("older", "MGSNSMART-18443 old release");

        Assert.assertEquals("nearest", ReleaseTraceService.previousReleaseBoundary(
                Arrays.asList(selected, featureMerge, nearestBoundary, olderSameRelease),
                selected.sha, Arrays.asList(selected, olderSameRelease)));
    }

    @Test
    public void matchesApplicationToOneOfSeveralRegisteredRepositories() {
        RepositoryRecord nadzor = repository("one", "FRONTEND/SMART/RINRIF/nadzor");
        RepositoryRecord violation = repository("two", "FRONTEND/SMART/RINRIF/violation");
        Assert.assertSame(violation,
                ReleaseTraceService.findRepository(Arrays.asList(nadzor, violation), "ui-violation"));
    }

    @Test
    public void appliesManualTasksAfterCreatingConcreteRepositoryTargets() {
        ReleaseTraceService.ParsedRelease parsed = new ReleaseTraceService.ParsedRelease();
        ReleaseTraceOverrides overrides = new ReleaseTraceOverrides();
        overrides.taskKeys.add("MGSNSMART-100");
        ReleaseTraceRepositoryOverride repository = new ReleaseTraceRepositoryOverride();
        repository.application = "nadzor";
        repository.repoId = "repo-1";
        overrides.repositories.add(repository);

        ReleaseTraceService.mergeOverrides(parsed, overrides);

        Assert.assertEquals(1, parsed.targets.size());
        Assert.assertTrue(parsed.targets.get("ui-nadzor").taskSources.containsKey("MGSNSMART-100"));
        Assert.assertFalse(parsed.targets.containsKey("manual"));
    }

    @Test
    public void fallsBackToReleaseParentOnlyWhenNoTaskEvidenceExists() {
        Assert.assertEquals("override", ReleaseTraceService.chooseBaseSha("override", "task-base", "parent"));
        Assert.assertEquals("task-base", ReleaseTraceService.chooseBaseSha(null, "task-base", "parent"));
        Assert.assertEquals("parent", ReleaseTraceService.chooseBaseSha(null, null, "parent"));
    }

    @Test
    public void keepsInvalidManualRepositoryAsIsolatedApplication() {
        ReleaseTraceRepositoryOverride override = new ReleaseTraceRepositoryOverride();
        override.repoId = "missing-repository";
        Assert.assertEquals("manual-repository",
                ReleaseTraceService.resolveOverrideApplication(override, null));
        override.application = "violation";
        Assert.assertEquals("ui-violation",
                ReleaseTraceService.resolveOverrideApplication(override, null));
    }

    @Test
    public void candidateVersionPrefersFilesOverUnrelatedComponentVersionInMessage() {
        Assert.assertEquals("5.0.1361-prod", ReleaseTraceService.resolveVersionEvidence(
                "5.0.1361-prod", "{\"version\":\"5.0.1361-prod\"}",
                "Обновить components-lib до 5.0.643", Arrays.asList("5.0.1361-prod"), true));
        Assert.assertEquals("5.0.42-release", ReleaseTraceService.resolveVersionEvidence(
                null, "{\"name\":\"ui\",\"version\":\"5.0.42-release\",\"dependency\":\"5.0.643\"}",
                "components 5.0.643", Arrays.asList("5.0.42-release"), true));
        Assert.assertNull(ReleaseTraceService.resolveVersionEvidence(
                null, null, "без версии", Arrays.asList("5.0.42-release"), false));
    }

    @Test
    public void unscopedCommentDoesNotSpreadTasksAcrossMultipleApplications() {
        JiraIssueData issue = new JiraIssueData();
        issue.key = "MGSNSMART-17464";
        issue.url = "https://jira.example/browse/MGSNSMART-17464";
        issue.description = "*ui-nadzor*\nMGSNSMART-15374 MGSNSMART-16775\n"
                + "*ui-violation*\nMGSNSMART-15374\n*ui-pm*\nMGSNSMART-17347";
        JiraCommentData comment = new JiraCommentData();
        comment.body = "Проверены MGSNSMART-15374 и новая MGSNSMART-19999";
        issue.comments.add(comment);

        ReleaseTraceService.ParsedRelease parsed = ReleaseTraceService.parseRelease(issue);

        Assert.assertEquals(Arrays.asList("MGSNSMART-15374", "MGSNSMART-16775"),
                new java.util.ArrayList<String>(parsed.targets.get("ui-nadzor").taskSources.keySet()));
        Assert.assertEquals(Arrays.asList("MGSNSMART-15374"),
                new java.util.ArrayList<String>(parsed.targets.get("ui-violation").taskSources.keySet()));
        Assert.assertEquals(Arrays.asList("MGSNSMART-17347"),
                new java.util.ArrayList<String>(parsed.targets.get("ui-pm").taskSources.keySet()));
        Assert.assertFalse(parsed.targets.get("ui-nadzor").taskSources.containsKey("MGSNSMART-19999"));
    }

    @Test
    public void mergeRequestLookupIncludesHistoricalJiraCommits() {
        ReleaseTraceCommit exact = traceCommit("exact", "MGSNSMART-1");
        ReleaseTraceCommit duplicateHistorical = traceCommit("exact", "MGSNSMART-1");
        ReleaseTraceCommit historical = traceCommit("historical", "MGSNSMART-1");
        ReleaseTraceCommit unrelated = traceCommit("unrelated", null);
        Assert.assertEquals(Arrays.asList("exact", "historical"),
                ReleaseTraceService.mergeRequestCommitShas(
                        Arrays.asList(exact), Arrays.asList(duplicateHistorical, historical, unrelated)));
    }

    @Test
    public void classifiesBuildMetadataSeparatelyFromFunctionalFiles() {
        Assert.assertTrue(ReleaseTraceService.isTechnical("package.json"));
        Assert.assertTrue(ReleaseTraceService.isTechnical("src/ver.txt"));
        Assert.assertTrue(ReleaseTraceService.isTechnical("package-lock.json"));
        Assert.assertFalse(ReleaseTraceService.isTechnical("src/app/violation-form.component.html"));
    }

    @Test
    public void mapsJiraErrorsToStableSourceSpecificCodes() {
        Assert.assertEquals("JIRA_AUTH_FAILED",
                JiraReadClient.mapJiraError(new AppException("AUTH_FAILED", "secret source error", 403)).getCode());
        Assert.assertEquals("JIRA_NOT_FOUND",
                JiraReadClient.mapJiraError(new AppException("JIRA_REQUEST_FAILED", "not found", 404)).getCode());
        Assert.assertEquals("JIRA_UNAVAILABLE",
                JiraReadClient.mapJiraError(new AppException("JIRA_REQUEST_FAILED", "timeout", 502)).getCode());
    }

    @Test
    public void flattensJiraRichTextWithoutAdfMetadata() throws Exception {
        String json = "{\"type\":\"doc\",\"version\":1,\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Полезный текст\"}]}]}";
        String text = JiraReadClient.flattenText(new ObjectMapper().readTree(json));
        Assert.assertEquals("Полезный текст", text);
    }

    private static JiraIssueLinkData link(String key, String relationship) {
        JiraIssueLinkData result = new JiraIssueLinkData();
        result.key = key;
        result.relationship = relationship;
        result.url = "https://jira.example/browse/" + key;
        return result;
    }

    private static CommitRecord commit(String sha, String subject) {
        return new CommitRecord(sha, subject, "", "Tester", "2026-01-01T00:00:00+03:00");
    }

    private static RepositoryRecord repository(String id, String name) {
        RepositoryRecord result = new RepositoryRecord();
        result.id = id;
        result.name = name;
        return result;
    }

    private static ReleaseTraceCommit traceCommit(String sha, String taskKey) {
        ReleaseTraceCommit result = new ReleaseTraceCommit();
        result.sha = sha;
        if (taskKey != null) {
            result.matchedTaskKeys.add(taskKey);
        }
        return result;
    }

    private static ReleaseTraceRunRequest request(String jiraKey) {
        ReleaseTraceRunRequest result = new ReleaseTraceRunRequest();
        result.jira = jiraKey;
        result.overrides = new ReleaseTraceOverrides();
        return result;
    }

    private static void assertInvalidRequest(ReleaseTraceRunRequest input) {
        try {
            ReleaseTraceService.normalizeRequest(input);
            Assert.fail("Invalid input must be rejected");
        } catch (AppException expected) {
            Assert.assertEquals("RELEASE_TRACE_INVALID_REQUEST", expected.getCode());
            Assert.assertEquals(400, expected.getStatusCode());
        }
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        Arrays.fill(result, value);
        return new String(result);
    }
}
