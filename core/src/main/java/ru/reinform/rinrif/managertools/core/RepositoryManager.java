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

    void updateMirror(RepositoryRecord repository) {
        Path localPath = Paths.get(repository.localPath);
        mirrorService.verifyMirror(localPath);
        mirrorService.updateMirror(localPath);
    }

    void removeMirror(RepositoryRecord repository) {
        mirrorService.removeMirror(Paths.get(repository.localPath));
    }

    String resolveRef(RepositoryRecord repository, String inputRef) {
        return refsService.resolveRef(Paths.get(repository.localPath), inputRef);
    }

    List<CommitRecord> listCommits(RepositoryRecord repository, GitLogOptions options) {
        return gitLogService.listCommits(Paths.get(repository.localPath), options);
    }

    String showDiff(RepositoryRecord repository, String sha) {
        return gitLogService.showDiff(Paths.get(repository.localPath), sha);
    }

    List<String> listChangedFiles(RepositoryRecord repository, String sha) {
        return gitLogService.listChangedFiles(Paths.get(repository.localPath), sha);
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
