package ru.reinform.rinrif.managertools.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.reinform.rinrif.managertools.model.ApiModels.AppError;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceApplication;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceRunRecord;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class ReleaseTraceStore {
    private static final TypeReference<ReleaseTraceRunRecord> RUN_TYPE = new TypeReference<ReleaseTraceRunRecord>() {};

    private final Path runsDirectory;
    private final ObjectMapper objectMapper;
    private final Map<String, ReleaseTraceRunRecord> cache = new ConcurrentHashMap<String, ReleaseTraceRunRecord>();

    ReleaseTraceStore(Path storageRoot, ObjectMapper objectMapper) {
        this.runsDirectory = storageRoot.resolve("meta").resolve("release-trace").resolve("runs");
        this.objectMapper = objectMapper;
    }

    synchronized ReleaseTraceRunRecord get(String runId) {
        String safeId = CoreUtils.safe(runId).replaceAll("[^A-Za-z0-9_-]", "");
        if (safeId.isEmpty() || !safeId.equals(runId)) {
            throw new AppException("RELEASE_TRACE_NOT_FOUND", "Release trace run was not found.", 404);
        }
        ReleaseTraceRunRecord cached = cache.get(safeId);
        if (cached != null) {
            return snapshot(cached);
        }
        Path file = runsDirectory.resolve(safeId + ".json");
        if (!Files.isRegularFile(file)) {
            throw new AppException("RELEASE_TRACE_NOT_FOUND", "Release trace run was not found.", 404);
        }
        try {
            ReleaseTraceRunRecord run = objectMapper.readValue(file.toFile(), RUN_TYPE);
            if (recoverInterruptedRun(run)) {
                persist(run);
            }
            cache.put(safeId, snapshot(run));
            return snapshot(run);
        } catch (IOException error) {
            throw new AppException("INTERNAL_ERROR", "Failed to read release trace state.", 500);
        }
    }

    synchronized void write(ReleaseTraceRunRecord run) {
        persist(run);
    }

    private void persist(ReleaseTraceRunRecord run) {
        run.updatedAt = CoreUtils.nowIso();
        ReleaseTraceRunRecord stored = snapshot(run);
        cache.put(run.runId, stored);
        new JsonFileStore<ReleaseTraceRunRecord>(
                runsDirectory.resolve(run.runId + ".json"), stored, RUN_TYPE, objectMapper
        ).write(stored);
    }

    private boolean recoverInterruptedRun(ReleaseTraceRunRecord run) {
        if (isTerminal(run.status)) {
            return false;
        }
        run.progress = 100;
        if (hasApplicationEvidence(run)) {
            run.status = ReleaseTraceStatus.partial;
            run.step = "partial";
            run.message = "Состав релиза восстановлен частично после перезапуска сервиса";
            run.error = null;
            addWarning(run, "Проверка была прервана перезапуском сервиса. Показаны данные, сохранённые до перезапуска; запустите проверку повторно для полного результата.");
            markInterruptedApplications(run);
        } else {
            run.status = ReleaseTraceStatus.failed;
            run.step = "failed";
            run.message = "Проверка прервана перезапуском сервиса";
            run.error = new AppError(
                    "RELEASE_TRACE_INTERRUPTED",
                    "Проверка не была завершена из-за перезапуска сервиса. Запустите её повторно.",
                    null
            );
        }
        return true;
    }

    private boolean isTerminal(ReleaseTraceStatus status) {
        return status == ReleaseTraceStatus.done
                || status == ReleaseTraceStatus.partial
                || status == ReleaseTraceStatus.failed;
    }

    private boolean hasApplicationEvidence(ReleaseTraceRunRecord run) {
        if (run.applications == null) {
            return false;
        }
        for (ReleaseTraceApplication application : run.applications) {
            if (application != null && (
                    !CoreUtils.safe(application.application).isEmpty()
                            || application.repository != null
                            || application.selectedRelease != null
                            || (application.tasks != null && !application.tasks.isEmpty())
                            || (application.commits != null && !application.commits.isEmpty())
                            || (application.historicalCommits != null && !application.historicalCommits.isEmpty())
                            || (application.files != null && !application.files.isEmpty())
                            || (application.technicalFiles != null && !application.technicalFiles.isEmpty())
                            || (application.mergeRequests != null && !application.mergeRequests.isEmpty()))) {
                return true;
            }
        }
        return false;
    }

    private void markInterruptedApplications(ReleaseTraceRunRecord run) {
        for (ReleaseTraceApplication application : run.applications) {
            if (application == null || "done".equals(application.status)
                    || "partial".equals(application.status) || "failed".equals(application.status)) {
                continue;
            }
            application.status = "partial";
            if (application.warnings == null) {
                application.warnings = new java.util.ArrayList<String>();
            }
            String warning = "Проверка этого приложения была прервана перезапуском сервиса.";
            if (!application.warnings.contains(warning)) {
                application.warnings.add(warning);
            }
        }
    }

    private void addWarning(ReleaseTraceRunRecord run, String warning) {
        if (run.warnings == null) {
            run.warnings = new java.util.ArrayList<String>();
        }
        if (!run.warnings.contains(warning)) {
            run.warnings.add(warning);
        }
    }

    private ReleaseTraceRunRecord snapshot(ReleaseTraceRunRecord run) {
        return objectMapper.convertValue(run, ReleaseTraceRunRecord.class);
    }
}
