package ru.reinform.rinrif.managertools.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class GitLogService {
    private final GitRunner gitRunner;

    GitLogService(GitRunner gitRunner) {
        this.gitRunner = gitRunner;
    }

    List<CommitRecord> listCommits(Path localPath, GitLogOptions options) {
        List<String> args = new ArrayList<String>();
        Collections.addAll(args, "-C", localPath.toString(), "log", options.resolvedRef, "--max-count=" + options.scanLimit, "--date=iso-strict", "--format=%H%x1f%s%x1f%b%x1f%an%x1f%aI%x1e");
        if (!options.includeMerges) {
            args.add("--no-merges");
        }
        if (options.firstParent) {
            args.add("--first-parent");
        }
        if (options.dateFrom != null) {
            args.add("--since=" + options.dateFrom);
        }
        if (options.dateTo != null) {
            args.add("--until=" + options.dateTo);
        }
        GitRunResult result = gitRunner.run(args);
        if (result.exitCode != 0) {
            throw new AppException("SEARCH_FAILED", "Failed to read commit list.", 500, result.stderr);
        }
        return parseGitLogOutput(result.stdout);
    }

    String showDiff(Path localPath, String sha) {
        GitRunResult result = gitRunner.run(GitSupport.args("-C", localPath.toString(), "show", "--format=", "--unified=0", "--find-renames", "--no-color", sha));
        if (result.exitCode != 0) {
            throw new AppException("DIFF_PARSE_FAILED", "Failed to read commit diff.", 500, result.stderr);
        }
        return result.stdout;
    }

    List<String> listChangedFiles(Path localPath, String sha) {
        GitRunResult result = gitRunner.run(GitSupport.args("-C", localPath.toString(), "diff-tree", "--no-commit-id", "--name-only", "-r", sha));
        if (result.exitCode != 0) {
            throw new AppException("DIFF_PARSE_FAILED", "Failed to read changed file list.", 500, result.stderr);
        }
        List<String> files = new ArrayList<String>();
        for (String line : result.stdout.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                files.add(line.trim());
            }
        }
        return files;
    }

    private List<CommitRecord> parseGitLogOutput(String stdout) {
        List<CommitRecord> result = new ArrayList<CommitRecord>();
        for (String rawRecord : stdout.split("\\u001e")) {
            String record = rawRecord.trim();
            if (record.isEmpty()) {
                continue;
            }
            String[] parts = record.split("\\u001f", -1);
            if (parts.length > 0 && !parts[0].isEmpty()) {
                result.add(new CommitRecord(parts[0], get(parts, 1), get(parts, 2), get(parts, 3), get(parts, 4)));
            }
        }
        return result;
    }

    private static String get(String[] values, int index) {
        return values.length > index ? values[index] : "";
    }
}

class GitLogOptions {
    String resolvedRef;
    boolean includeMerges;
    boolean firstParent;
    String dateFrom;
    String dateTo;
    int scanLimit;
}

class CommitRecord {
    final String sha;
    final String subject;
    final String body;
    final String authorName;
    final String authoredAt;

    CommitRecord(String sha, String subject, String body, String authorName, String authoredAt) {
        this.sha = sha;
        this.subject = subject;
        this.body = body;
        this.authorName = authorName;
        this.authoredAt = authoredAt;
    }
}
