package ru.reinform.rinrif.managertools.core;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class GitLogServiceTest {
    @Test
    public void decodesQuotedUtf8PathsProducedByRuntimeJGit() {
        String encoded = "servi\\321\\201es/file name.ts";
        String diff = "diff --git \"a/" + encoded + "\" \"b/" + encoded + "\"\n"
                + "--- \"a/" + encoded + "\"\n"
                + "+++ \"b/" + encoded + "\"\n"
                + "@@ -0,0 +1 @@\n+line\n";

        List<FileDiffRange> parsed = DiffRangeParser.parse(diff);

        Assert.assertEquals(1, parsed.size());
        Assert.assertEquals("serviсes/file name.ts", parsed.get(0).path);
        Assert.assertEquals(1, parsed.get(0).ranges.size());
    }

    @Test
    public void calculatesBaseBeforeSelectedTaskAndNetRange() throws Exception {
        Path directory = Files.createTempDirectory("release-trace-git-");
        Git git = Git.init().setDirectory(directory.toFile()).call();
        try {
            Path functional = directory.resolve("form.component.html");
            Path version = directory.resolve("ver.txt");
            Files.write(functional, Arrays.asList("before"), StandardCharsets.UTF_8);
            Files.write(version, Arrays.asList("1.0.0-prod"), StandardCharsets.UTF_8);
            git.add().addFilepattern("form.component.html").addFilepattern("ver.txt").call();
            RevCommit base = git.commit().setAuthor("Test", "test@example.com").setMessage("previous release 1.0.0-prod").call();

            Files.write(functional, Arrays.asList("after"), StandardCharsets.UTF_8);
            git.add().addFilepattern("form.component.html").call();
            RevCommit task = git.commit().setAuthor("Test", "test@example.com").setMessage("MGSNSMART-18233 change form").call();

            Path technical = directory.resolve("package.json");
            Files.write(technical, Arrays.asList("{\"name\":\"test\"}"), StandardCharsets.UTF_8);
            Path unicode = directory.resolve("serviсes").resolve("violation-rest.service.ts");
            Files.createDirectories(unicode.getParent());
            Files.write(unicode, Arrays.asList("export const value = 1;"), StandardCharsets.UTF_8);
            git.add().addFilepattern("package.json").call();
            git.add().addFilepattern("serviсes/violation-rest.service.ts").call();
            git.commit().setAuthor("Test", "test@example.com").setMessage("intervening technical commit").call();

            Files.write(version, Arrays.asList("1.0.1-prod"), StandardCharsets.UTF_8);
            git.add().addFilepattern("ver.txt").call();
            RevCommit release = git.commit().setAuthor("Test", "test@example.com").setMessage("MGSNSMART-18443 release 1.0.1-prod").call();

            GitLogService service = new GitLogService();
            Path gitDirectory = directory.resolve(".git");
            GitLogOptions options = new GitLogOptions();
            options.resolvedRef = release.getName();
            options.includeMerges = true;
            options.firstParent = true;
            options.scanLimit = 100;
            List<CommitRecord> firstParent = service.listCommits(gitDirectory, options);
            Assert.assertEquals(base.getName(),
                    ReleaseTraceService.previousReleaseBoundary(firstParent, release.getName()));
            List<CommitRecord> evidenceRange = service.listRangeCommits(
                    gitDirectory, base.getName(), release.getName(), 100);
            boolean taskFound = false;
            for (CommitRecord evidence : evidenceRange) {
                if (evidence.subject.contains("MGSNSMART-18233")) {
                    taskFound = true;
                }
            }
            Assert.assertTrue(taskFound);
            Assert.assertEquals(base.getName(),
                    service.findBaseBeforeCommits(gitDirectory, release.getName(), Arrays.asList(task.getName())));
            List<String> files = service.listChangedFiles(gitDirectory, base.getName(), release.getName());
            Assert.assertTrue(files.contains("form.component.html"));
            Assert.assertTrue(files.contains("ver.txt"));
            Assert.assertTrue(files.contains("package.json"));
            Assert.assertTrue(files.contains("serviсes/violation-rest.service.ts"));
            Assert.assertEquals("1.0.0-prod\n", service.readFileAt(gitDirectory, base.getName(), "ver.txt"));
            Assert.assertEquals("1.0.1-prod\n", service.readFileAt(gitDirectory, release.getName(), "ver.txt"));
            List<FileDiffRange> diffRanges = DiffRangeParser.parse(service.showDiff(gitDirectory, base.getName(), release.getName()));
            Assert.assertFalse(diffRanges.isEmpty());
            boolean hasFunctionalLine = false;
            boolean hasUnicodePath = false;
            for (FileDiffRange diffRange : diffRanges) {
                if ("form.component.html".equals(diffRange.path) && !diffRange.ranges.isEmpty()) {
                    hasFunctionalLine = true;
                }
                Assert.assertFalse(diffRange.path.contains("\\321"));
                Assert.assertFalse(diffRange.path.startsWith("\""));
                if ("serviсes/violation-rest.service.ts".equals(diffRange.path)) {
                    hasUnicodePath = true;
                }
            }
            Assert.assertTrue(hasFunctionalLine);
            Assert.assertTrue(hasUnicodePath);
            List<CommitRecord> commits = service.listRangeCommits(gitDirectory, base.getName(), release.getName(), 10);
            Assert.assertEquals(3, commits.size());
        } finally {
            git.close();
        }
    }

    @Test
    public void repeatedTaskUsesNewestEvidenceAndStartsDiffAfterOlderOccurrence() throws Exception {
        Path directory = Files.createTempDirectory("release-trace-repeated-task-");
        Git git = Git.init().setDirectory(directory.toFile()).call();
        try {
            Path version = directory.resolve("ver.txt");
            Files.write(version, Arrays.asList("1.0.0-prod"), StandardCharsets.UTF_8);
            git.add().addFilepattern("ver.txt").call();
            RevCommit boundary = git.commit().setAuthor("Test", "test@example.com")
                    .setMessage("ver incr.").call();

            Path oldFile = directory.resolve("old-change.html");
            Files.write(oldFile, Arrays.asList("old"), StandardCharsets.UTF_8);
            git.add().addFilepattern("old-change.html").call();
            RevCommit olderTask = git.commit().setAuthor("Test", "test@example.com")
                    .setMessage("MGSNSMART-17347 first implementation").call();

            Path finalFile = directory.resolve("prepare-mp-new.component.ts");
            Files.write(finalFile, Arrays.asList("final"), StandardCharsets.UTF_8);
            git.add().addFilepattern("prepare-mp-new.component.ts").call();
            RevCommit newestTask = git.commit().setAuthor("Test", "test@example.com")
                    .setMessage("MGSNSMART-17347 final implementation").call();

            Files.write(version, Arrays.asList("1.0.1-prod"), StandardCharsets.UTF_8);
            git.add().addFilepattern("ver.txt").call();
            RevCommit release = git.commit().setAuthor("Test", "test@example.com")
                    .setMessage("MGSNSMART-17464 Подготовить релизную ветку").call();

            GitLogService service = new GitLogService();
            Path gitDirectory = directory.resolve(".git");
            List<CommitRecord> range = service.listRangeCommits(
                    gitDirectory, boundary.getName(), release.getName(), 100);
            List<CommitRecord> selected = ReleaseTraceService.selectLatestTaskCommits(
                    range, Arrays.asList("MGSNSMART-17347"));

            Assert.assertEquals(1, selected.size());
            Assert.assertEquals(newestTask.getName(), selected.get(0).sha);
            String base = service.findBaseBeforeCommits(gitDirectory, release.getName(),
                    Arrays.asList(selected.get(0).sha));
            Assert.assertEquals(olderTask.getName(), base);
            List<String> files = service.listChangedFiles(gitDirectory, base, release.getName());
            Assert.assertTrue(files.contains("prepare-mp-new.component.ts"));
            Assert.assertTrue(files.contains("ver.txt"));
            Assert.assertFalse(files.contains("old-change.html"));
        } finally {
            git.close();
        }
    }

    @Test
    public void taskBeforeAnyReleaseBoundaryRemainsHistorical() throws Exception {
        Path directory = Files.createTempDirectory("release-trace-no-boundary-");
        Git git = Git.init().setDirectory(directory.toFile()).call();
        try {
            Path oldFile = directory.resolve("old-task.html");
            Files.write(oldFile, Arrays.asList("old"), StandardCharsets.UTF_8);
            git.add().addFilepattern("old-task.html").call();
            RevCommit oldTask = git.commit().setAuthor("Test", "test@example.com")
                    .setMessage("MGSNSMART-18416 old implementation").call();

            Path unrelated = directory.resolve("unrelated.ts");
            Files.write(unrelated, Arrays.asList("unrelated"), StandardCharsets.UTF_8);
            git.add().addFilepattern("unrelated.ts").call();
            RevCommit releaseParent = git.commit().setAuthor("Test", "test@example.com")
                    .setMessage("ordinary refactoring").call();

            Path version = directory.resolve("ver.txt");
            Files.write(version, Arrays.asList("1.0.1-release"), StandardCharsets.UTF_8);
            git.add().addFilepattern("ver.txt").call();
            RevCommit release = git.commit().setAuthor("Test", "test@example.com")
                    .setMessage("MGSNSMART-18721 release target").call();

            GitLogService service = new GitLogService();
            Path gitDirectory = directory.resolve(".git");
            GitLogOptions options = new GitLogOptions();
            options.resolvedRef = release.getName();
            options.includeMerges = true;
            options.firstParent = true;
            options.scanLimit = 100;
            List<CommitRecord> firstParent = service.listCommits(gitDirectory, options);
            String boundary = ReleaseTraceService.previousReleaseBoundary(firstParent, release.getName());
            Assert.assertNull(boundary);

            List<CommitRecord> entireHistory = service.listRangeCommits(
                    gitDirectory, null, release.getName(), 100);
            List<CommitRecord> unboundedMatches = ReleaseTraceService.selectLatestTaskCommits(
                    entireHistory, Arrays.asList("MGSNSMART-18416"));
            Assert.assertEquals(oldTask.getName(), unboundedMatches.get(0).sha);
            Assert.assertTrue(ReleaseTraceService.selectLatestTaskCommits(
                    entireHistory, Arrays.asList("MGSNSMART-18416"), boundary).isEmpty());
            Assert.assertEquals(releaseParent.getName(), service.parentOf(gitDirectory, release.getName()));
            Assert.assertEquals(Arrays.asList("ver.txt"), service.listChangedFiles(
                    gitDirectory, releaseParent.getName(), release.getName()));
        } finally {
            git.close();
        }
    }
}
