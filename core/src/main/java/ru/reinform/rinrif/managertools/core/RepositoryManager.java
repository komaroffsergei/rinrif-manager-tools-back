package ru.reinform.rinrif.managertools.core;

import ru.reinform.rinrif.managertools.model.ApiModels.RepositoryRecord;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import ru.reinform.rinrif.managertools.model.ApiModels.RepositoryRefsResponse;

class RepositoryManager {
    private final GitMirrorService mirrorService;
    private final GitRefsService refsService;
    private final GitLogService gitLogService;

    RepositoryManager(GitMirrorService mirrorService, GitRefsService refsService, GitLogService gitLogService) {
        this.mirrorService = mirrorService;
        this.refsService = refsService;
        this.gitLogService = gitLogService;
    }

    void cloneMirror(RepositoryRecord repository) {
        mirrorService.cloneMirror(repository.url, Paths.get(repository.localPath));
    }

    void cloneMirror(RepositoryRecord repository, String cloneUrl) {
        mirrorService.cloneMirror(cloneUrl, Paths.get(repository.localPath));
    }

    void updateMirror(RepositoryRecord repository) {
        Path localPath = Paths.get(repository.localPath);
        mirrorService.verifyMirror(localPath);
        mirrorService.updateMirror(localPath);
    }

    void updateMirror(RepositoryRecord repository, String inputRef) {
        Path localPath = Paths.get(repository.localPath);
        mirrorService.verifyMirror(localPath);
        mirrorService.updateMirror(localPath, inputRef);
    }

    void removeMirror(RepositoryRecord repository) {
        mirrorService.removeMirror(Paths.get(repository.localPath));
    }

    String resolveRef(RepositoryRecord repository, String inputRef) {
        return refsService.resolveRef(Paths.get(repository.localPath), inputRef);
    }

    RepositoryRefsResponse listRefs(RepositoryRecord repository) {
        try {
            return mirrorService.listRemoteRefs(repository.url, repository.id);
        } catch (RuntimeException remoteError) {
            try {
                return refsService.listRefs(Paths.get(repository.localPath), repository.id);
            } catch (RuntimeException localError) {
                throw safeRefsFailure(remoteError, localError);
            }
        }
    }

    static AppException safeRefsFailure(RuntimeException remoteError, RuntimeException localError) {
        if (hasCode(remoteError, "AUTH_FAILED") && hasCode(localError, "AUTH_FAILED")) {
            return new AppException("REFS_AUTH_FAILED", "Не удалось авторизоваться для чтения веток репозитория.", 401);
        }
        return new AppException("REFS_UNAVAILABLE", "Не удалось получить ветки репозитория.", 503);
    }

    private static boolean hasCode(RuntimeException error, String code) {
        return error instanceof AppException && code.equals(((AppException) error).getCode());
    }

    List<CommitRecord> listCommits(RepositoryRecord repository, GitLogOptions options) {
        return gitLogService.listCommits(Paths.get(repository.localPath), options);
    }

    String showDiff(RepositoryRecord repository, String sha) {
        return gitLogService.showDiff(Paths.get(repository.localPath), sha);
    }

    String showDiff(RepositoryRecord repository, String baseSha, String targetSha) {
        return gitLogService.showDiff(Paths.get(repository.localPath), baseSha, targetSha);
    }

    List<String> listChangedFiles(RepositoryRecord repository, String sha) {
        return gitLogService.listChangedFiles(Paths.get(repository.localPath), sha);
    }

    List<String> listChangedFiles(RepositoryRecord repository, String baseSha, String targetSha) {
        return gitLogService.listChangedFiles(Paths.get(repository.localPath), baseSha, targetSha);
    }

    String readFileAt(RepositoryRecord repository, String sha, String filePath) {
        return gitLogService.readFileAt(Paths.get(repository.localPath), sha, filePath);
    }

    List<CommitRecord> listRangeCommits(RepositoryRecord repository, String baseSha, String targetSha, int limit) {
        return gitLogService.listRangeCommits(Paths.get(repository.localPath), baseSha, targetSha, limit);
    }

    String parentOf(RepositoryRecord repository, String sha) {
        return gitLogService.parentOf(Paths.get(repository.localPath), sha);
    }

    boolean isAncestor(RepositoryRecord repository, String ancestorSha, String descendantSha) {
        return gitLogService.isAncestor(Paths.get(repository.localPath), ancestorSha, descendantSha);
    }

    String findBaseBeforeCommits(RepositoryRecord repository, String targetSha, List<String> commitShas) {
        return gitLogService.findBaseBeforeCommits(Paths.get(repository.localPath), targetSha, commitShas);
    }

    long calculateRepositorySize(Path localPath) {
        if (!Files.exists(localPath)) {
            return 0L;
        }
        final long[] size = {0L};
        try {
            Files.walkFileTree(localPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    size[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException error) {
            throw new AppException("BROKEN_REPOSITORY", "Failed to calculate repository size.", 500, error.getMessage());
        }
        return size[0];
    }
}
