package ru.reinform.rinrif.managertools.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

class GitAuth {
    private final AppConfig config;

    GitAuth(AppConfig config) {
        this.config = config;
    }

    List<String> cloneAndFetchArgs() {
        List<String> args = new ArrayList<String>();
        if (config.gitLabPat != null) {
            args.add("-c");
            args.add("http.extraHeader=Authorization: Basic " + encodedAuth());
        }
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

    void applyCredentials(TransportCommand<?, ?> command) {
        if (config.gitLabPat != null) {
            command.setCredentialsProvider(new UsernamePasswordCredentialsProvider("oauth2", config.gitLabPat));
        }
        command.setTimeout((int) Math.max(1L, TimeUnit.MILLISECONDS.toSeconds(config.gitCommandTimeoutMs)));
    }

    void applyCredentials(TransportCommand<?, ?> command, String remoteUrl) {
        if (config.gitLabPat != null) {
            applyCredentials(command);
            return;
        }
        UsernamePasswordCredentialsProvider credentials = credentialsFromUrl(remoteUrl);
        if (credentials != null) {
            command.setCredentialsProvider(credentials);
        }
        command.setTimeout((int) Math.max(1L, TimeUnit.MILLISECONDS.toSeconds(config.gitCommandTimeoutMs)));
    }

    private UsernamePasswordCredentialsProvider credentialsFromUrl(String remoteUrl) {
        try {
            String userInfo = new URL(remoteUrl).getUserInfo();
            if (userInfo == null || userInfo.trim().isEmpty()) {
                return null;
            }
            String[] parts = userInfo.split(":", 2);
            String username = decode(parts[0]);
            String password = parts.length > 1 ? decode(parts[1]) : "";
            return new UsernamePasswordCredentialsProvider(username, password);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String decode(String value) throws Exception {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
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
    private final GitAuth gitAuth;

    GitMirrorService(GitAuth gitAuth) {
        this.gitAuth = gitAuth;
    }

    void cloneMirror(String repositoryUrl, Path localPath) {
        try {
            Files.createDirectories(localPath.getParent());
            if (readRemoteRefs(repositoryUrl).isEmpty()) {
                throw new AppException("CLONE_FAILED", "Repository was not found or is not accessible.", 404);
            }
            Git git = Git.init()
                    .setBare(true)
                    .setDirectory(localPath.toFile())
                    .call();
            try {
                StoredConfig config = git.getRepository().getConfig();
                config.setString("remote", "origin", "url", repositoryUrl);
                config.save();
            } finally {
                git.close();
            }
        } catch (IOException error) {
            throw new AppException("CLONE_FAILED", "Failed to prepare mirror directory.", 500, error.getMessage());
        } catch (GitAPIException error) {
            throw mapGitFailure(redact(error.getMessage()), "CLONE_FAILED", "Failed to clone repository");
        }
    }

    void updateMirror(Path localPath) {
        updateMirror(localPath, null);
    }

    void updateMirror(Path localPath, String inputRef) {
        try {
            Repository repository = GitSupport.openRepository(localPath);
            try {
                String remoteRef = inputRef == null ? selectDefaultRemoteRef(repository) : resolveRemoteRef(repository, inputRef);
                Git git = new Git(repository);
                try {
                    fetchRef(git, remoteRef);
                } finally {
                    git.close();
                }
            } finally {
                repository.close();
            }
        } catch (IOException error) {
            throw new AppException("BROKEN_REPOSITORY", "Local repository mirror is missing or broken.", 409, error.getMessage());
        } catch (GitAPIException error) {
            throw mapGitFailure(redact(error.getMessage()), "FETCH_FAILED", "Failed to update repository");
        }
    }

    void verifyMirror(Path localPath) {
        Repository repository = null;
        try {
            repository = GitSupport.openRepository(localPath);
            if (!repository.isBare() || !repository.getObjectDatabase().exists()) {
                throw new AppException("BROKEN_REPOSITORY", "Local repository mirror is missing or broken.", 409);
            }
        } catch (IOException error) {
            throw new AppException("BROKEN_REPOSITORY", "Local repository mirror is missing or broken.", 409);
        } finally {
            if (repository != null) {
                repository.close();
            }
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

    private void fetchRef(Git git, String remoteRef) throws GitAPIException {
        if (remoteRef == null) {
            return;
        }
        String localRef = remoteRef;
        if (remoteRef.startsWith("refs/heads/")) {
            localRef = "refs/remotes/origin/" + remoteRef.substring("refs/heads/".length());
        }
        FetchCommand command = git.fetch()
                .setRemote("origin")
                .setRemoveDeletedRefs(true)
                .setRefSpecs(new RefSpec("+" + remoteRef + ":" + localRef));
        gitAuth.applyCredentials(command, readRemoteUrl(git.getRepository()));
        command.call();
    }

    private String selectDefaultRemoteRef(Repository repository) throws GitAPIException {
        Set<String> refs = readRemoteRefNames(readRemoteUrl(repository));
        for (String candidate : GitSupport.args("refs/heads/dev", "refs/heads/release", "refs/heads/prod", "refs/heads/master", "refs/heads/main")) {
            if (refs.contains(candidate)) {
                return candidate;
            }
        }
        for (String ref : refs) {
            if (ref.startsWith("refs/heads/")) {
                return ref;
            }
        }
        return null;
    }

    private String resolveRemoteRef(Repository repository, String inputRef) throws GitAPIException {
        String normalizedInput = CoreUtils.safe(inputRef).trim();
        Set<String> refs = readRemoteRefNames(readRemoteUrl(repository));
        for (String candidate : remoteRefCandidates(normalizedInput)) {
            if (refs.contains(candidate)) {
                return candidate;
            }
        }
        throw new AppException("REF_NOT_FOUND", "Requested branch or ref was not found.", 404);
    }

    private Set<String> readRemoteRefNames(String remoteUrl) throws GitAPIException {
        Set<String> refs = new LinkedHashSet<String>();
        for (Ref ref : readRemoteRefs(remoteUrl)) {
            refs.add(ref.getName());
        }
        return refs;
    }

    private Collection<Ref> readRemoteRefs(String remoteUrl) throws GitAPIException {
        org.eclipse.jgit.api.LsRemoteCommand command = Git.lsRemoteRepository()
                .setRemote(remoteUrl)
                .setHeads(true)
                .setTags(true);
        gitAuth.applyCredentials(command, remoteUrl);
        return command.call();
    }

    private List<String> remoteRefCandidates(String normalizedInput) {
        List<String> candidates = new ArrayList<String>();
        candidates.add(normalizedInput);
        candidates.add("refs/heads/" + normalizedInput);
        candidates.add("refs/tags/" + normalizedInput);
        if (normalizedInput.startsWith("origin/")) {
            candidates.add("refs/heads/" + normalizedInput.substring("origin/".length()));
        }
        if (normalizedInput.startsWith("refs/remotes/origin/")) {
            candidates.add("refs/heads/" + normalizedInput.substring("refs/remotes/origin/".length()));
        }
        return candidates;
    }

    private String readRemoteUrl(Repository repository) {
        String remoteUrl = repository.getConfig().getString("remote", "origin", "url");
        if (CoreUtils.safe(remoteUrl).trim().isEmpty()) {
            throw new AppException("BROKEN_REPOSITORY", "Remote repository URL is missing.", 409);
        }
        return remoteUrl;
    }

    private String redact(String value) {
        String redacted = gitAuth.redact(value);
        return redacted == null ? null : redacted.replaceAll("(?i)(https?://)[^\\s/@]+@", "$1***@");
    }

    private AppException mapGitFailure(String stderr, String defaultCode, String fallbackMessage) {
        String lowered = CoreUtils.safe(stderr).toLowerCase(Locale.ROOT);
        if (lowered.contains("authentication failed") || lowered.contains("authentication is required") || lowered.contains("http basic: access denied") || lowered.contains("access denied") || lowered.contains("not authorized") || lowered.contains("could not read username")) {
            return new AppException("AUTH_FAILED", "GitLab authentication failed.", 401);
        }
        if (lowered.contains("repository not found") || lowered.contains("not found")) {
            return new AppException(defaultCode, "Repository was not found or is not accessible.", 404);
        }
        return new AppException(defaultCode, fallbackMessage, 500, stderr.trim());
    }
}

class GitRefsService {
    GitRefsService() {
    }

    String resolveRef(Path localPath, String inputRef) {
        String normalizedInput = CoreUtils.safe(inputRef).trim();
        if (normalizedInput.isEmpty()) {
            throw new AppException("REF_NOT_FOUND", "Ref or branch is required.", 400);
        }
        Set<String> refs = new HashSet<String>();
        Repository repository = null;
        try {
            repository = GitSupport.openRepository(localPath);
            for (Ref ref : repository.getAllRefs().values()) {
                if (ref.getName().startsWith("refs/heads/") || ref.getName().startsWith("refs/tags/") || ref.getName().startsWith("refs/remotes/")) {
                    refs.add(ref.getName());
                }
            }
        } catch (IOException error) {
            throw new AppException("BROKEN_REPOSITORY", "Failed to read local mirror refs.", 409);
        } finally {
            if (repository != null) {
                repository.close();
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

    static Repository openRepository(Path localPath) throws IOException {
        return new FileRepositoryBuilder()
                .setGitDir(localPath.toFile())
                .build();
    }
}
