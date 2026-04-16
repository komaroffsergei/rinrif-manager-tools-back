package ru.reinform.rinrif.managertools.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class GitAuth {
    private final AppConfig config;

    GitAuth(AppConfig config) {
        this.config = config;
    }

    List<String> cloneAndFetchArgs() {
        if (config.gitLabPat == null) {
            throw new AppException("AUTH_FAILED", "GitLab PAT is not configured.", 500);
        }
        List<String> args = new ArrayList<String>();
        args.add("-c");
        args.add("http.extraHeader=Authorization: Basic " + encodedAuth());
        return args;
    }

    String redact(String value) {
        if (value == null) {
            return null;
        }
        String result = value;
        if (config.gitLabPat != null) {
            result = result.replace(config.gitLabPat, "***").replace(encodedAuth(), "***");
        }
        return result;
    }

    private String encodedAuth() {
        return Base64.getEncoder().encodeToString(("oauth2:" + config.gitLabPat).getBytes(StandardCharsets.UTF_8));
    }
}

class GitRunner {
    private final AppConfig config;
    private final GitAuth auth;
    private final ExecutorService streamReaderExecutor = Executors.newCachedThreadPool();

    GitRunner(AppConfig config, GitAuth auth) {
        this.config = config;
        this.auth = auth;
    }

    GitRunResult run(List<String> args) {
        List<String> command = new ArrayList<String>();
        command.add("git");
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command);
        try {
            Process process = builder.start();
            Future<String> stdout = streamReaderExecutor.submit(new StreamReader(process.getInputStream()));
            Future<String> stderr = streamReaderExecutor.submit(new StreamReader(process.getErrorStream()));
            boolean finished = process.waitFor(config.gitCommandTimeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new GitRunResult("", "Git command timed out", 1);
            }
            return new GitRunResult(auth.redact(stdout.get()), auth.redact(stderr.get()), process.exitValue());
        } catch (Exception error) {
            return new GitRunResult("", auth.redact(error.getMessage()), 1);
        }
    }

    private static class StreamReader implements Callable<String> {
        private final InputStream stream;

        StreamReader(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public String call() throws Exception {
            StringBuilder result = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append('\n');
            }
            return result.toString();
        }
    }
}

class GitRunResult {
    final String stdout;
    final String stderr;
    final int exitCode;

    GitRunResult(String stdout, String stderr, int exitCode) {
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
        this.exitCode = exitCode;
    }
}

class GitMirrorService {
    private final GitRunner gitRunner;
    private final GitAuth gitAuth;

    GitMirrorService(GitRunner gitRunner, GitAuth gitAuth) {
        this.gitRunner = gitRunner;
        this.gitAuth = gitAuth;
    }

    void cloneMirror(String repositoryUrl, Path localPath) {
        try {
            Files.createDirectories(localPath.getParent());
        } catch (IOException error) {
            throw new AppException("CLONE_FAILED", "Failed to prepare mirror directory.", 500, error.getMessage());
        }
        List<String> args = new ArrayList<String>(gitAuth.cloneAndFetchArgs());
        Collections.addAll(args, "clone", "--mirror", repositoryUrl, localPath.toString());
        GitRunResult result = gitRunner.run(args);
        if (result.exitCode != 0) {
            throw mapGitFailure(result.stderr, "CLONE_FAILED", "Failed to clone repository");
        }
    }

    void updateMirror(Path localPath) {
        List<String> args = new ArrayList<String>(gitAuth.cloneAndFetchArgs());
        Collections.addAll(args, "-C", localPath.toString(), "fetch", "--prune", "origin");
        GitRunResult result = gitRunner.run(args);
        if (result.exitCode != 0) {
            throw mapGitFailure(result.stderr, "FETCH_FAILED", "Failed to update repository");
        }
    }

    void verifyMirror(Path localPath) {
        GitRunResult result = gitRunner.run(GitSupport.args("-C", localPath.toString(), "rev-parse", "--is-bare-repository"));
        if (result.exitCode != 0 || !"true".equals(result.stdout.trim())) {
            throw new AppException("BROKEN_REPOSITORY", "Local repository mirror is missing or broken.", 409);
        }
    }

    void removeMirror(Path localPath) {
        if (!Files.exists(localPath)) {
            return;
        }
        try {
            Files.walkFileTree(localPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException error) {
            throw new AppException("DELETE_FAILED", "Failed to delete local repository mirror.", 500, error.getMessage());
        }
    }

    private AppException mapGitFailure(String stderr, String defaultCode, String fallbackMessage) {
        String lowered = CoreUtils.safe(stderr).toLowerCase(Locale.ROOT);
        if (lowered.contains("authentication failed") || lowered.contains("http basic: access denied") || lowered.contains("access denied") || lowered.contains("could not read username")) {
            return new AppException("AUTH_FAILED", "GitLab authentication failed.", 401);
        }
        if (lowered.contains("repository not found") || lowered.contains("not found")) {
            return new AppException(defaultCode, "Repository was not found or is not accessible.", 404);
        }
        return new AppException(defaultCode, fallbackMessage, 500, stderr.trim());
    }
}

class GitRefsService {
    private final GitRunner gitRunner;

    GitRefsService(GitRunner gitRunner) {
        this.gitRunner = gitRunner;
    }

    String resolveRef(Path localPath, String inputRef) {
        String normalizedInput = CoreUtils.safe(inputRef).trim();
        if (normalizedInput.isEmpty()) {
            throw new AppException("REF_NOT_FOUND", "Ref or branch is required.", 400);
        }
        GitRunResult result = gitRunner.run(GitSupport.args("-C", localPath.toString(), "for-each-ref", "--format=%(refname)", "refs/heads", "refs/tags", "refs/remotes"));
        if (result.exitCode != 0) {
            throw new AppException("BROKEN_REPOSITORY", "Failed to read local mirror refs.", 409);
        }
        Set<String> refs = new HashSet<String>();
        for (String line : result.stdout.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                refs.add(line.trim());
            }
        }
        List<String> candidates = new ArrayList<String>();
        candidates.add(normalizedInput);
        candidates.add("refs/heads/" + normalizedInput);
        candidates.add("refs/tags/" + normalizedInput);
        candidates.add("refs/remotes/" + normalizedInput);
        candidates.add("refs/remotes/origin/" + normalizedInput);
        if (normalizedInput.startsWith("origin/")) {
            candidates.add("refs/heads/" + normalizedInput.substring("origin/".length()));
        }
        for (String candidate : candidates) {
            if (refs.contains(candidate)) {
                return candidate;
            }
        }
        throw new AppException("REF_NOT_FOUND", "Requested branch or ref was not found.", 404);
    }
}

class GitSupport {
    private GitSupport() {
    }

    static List<String> args(String... values) {
        List<String> result = new ArrayList<String>();
        Collections.addAll(result, values);
        return result;
    }
}
