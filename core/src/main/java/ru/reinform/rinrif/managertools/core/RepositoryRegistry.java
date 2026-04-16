package ru.reinform.rinrif.managertools.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.reinform.rinrif.managertools.model.ApiModels.RepositoryRecord;
import ru.reinform.rinrif.managertools.model.ApiModels.RepositoryStatus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class RepositoryRegistry {
    private static final TypeReference<List<RepositoryRecord>> REPOSITORY_LIST_TYPE = new TypeReference<List<RepositoryRecord>>() {};

    private final JsonFileStore<List<RepositoryRecord>> store;

    RepositoryRegistry(Path storageRoot, ObjectMapper objectMapper) {
        this.store = new JsonFileStore<List<RepositoryRecord>>(
                storageRoot.resolve("meta").resolve("repositories.json"),
                new ArrayList<RepositoryRecord>(),
                REPOSITORY_LIST_TYPE,
                objectMapper
        );
    }

    synchronized void ensureInitialized() {
        if (!store.exists()) {
            store.write(new ArrayList<RepositoryRecord>());
        }
    }

    synchronized List<RepositoryRecord> listRepositories() {
        return new ArrayList<RepositoryRecord>(store.read());
    }

    synchronized RepositoryRecord getRepository(String repoId) {
        RepositoryRecord repository = findRepository(repoId);
        if (repository == null) {
            throw new AppException("REPOSITORY_NOT_FOUND", "Repository was not found.", 404);
        }
        return repository;
    }

    synchronized RepositoryRecord findRepository(String repoId) {
        for (RepositoryRecord repository : store.read()) {
            if (CoreUtils.safe(repository.id).equals(repoId)) {
                return repository;
            }
        }
        return null;
    }

    synchronized RepositoryRecord findByNormalizedUrl(String normalizedUrl) {
        for (RepositoryRecord repository : store.read()) {
            if (CoreUtils.safe(repository.normalizedUrl).equals(normalizedUrl)) {
                return repository;
            }
        }
        return null;
    }

    synchronized void createRepository(RepositoryRecord repository) {
        List<RepositoryRecord> repositories = store.read();
        repositories.add(repository);
        store.write(repositories);
    }

    synchronized void updateRepository(RepositoryRecord record) {
        List<RepositoryRecord> repositories = store.read();
        for (int i = 0; i < repositories.size(); i++) {
            if (CoreUtils.safe(repositories.get(i).id).equals(record.id)) {
                repositories.set(i, record);
                store.write(repositories);
                return;
            }
        }
        throw new AppException("REPOSITORY_NOT_FOUND", "Repository was not found.", 404);
    }

    synchronized void updateRepositoryStatus(String repoId, RepositoryStatus status) {
        RepositoryRecord repository = getRepository(repoId);
        repository.status = status;
        repository.updatedAt = CoreUtils.nowIso();
        updateRepository(repository);
    }

    synchronized void deleteRepository(String repoId) {
        List<RepositoryRecord> repositories = store.read();
        List<RepositoryRecord> next = new ArrayList<RepositoryRecord>();
        for (RepositoryRecord repository : repositories) {
            if (!CoreUtils.safe(repository.id).equals(repoId)) {
                next.add(repository);
            }
        }
        store.write(next);
    }
}
