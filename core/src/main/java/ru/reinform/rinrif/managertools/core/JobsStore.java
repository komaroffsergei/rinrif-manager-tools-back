package ru.reinform.rinrif.managertools.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.reinform.rinrif.managertools.model.ApiModels;
import ru.reinform.rinrif.managertools.model.ApiModels.JobStatus;
import ru.reinform.rinrif.managertools.model.ApiModels.JobType;
import ru.reinform.rinrif.managertools.model.ApiModels.SearchJobRecord;
import ru.reinform.rinrif.managertools.model.ApiModels.SearchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class JobsStore {
    private static final TypeReference<SearchJobRecord> JOB_TYPE = new TypeReference<SearchJobRecord>() {};

    private final Path jobsDirectory;
    private final ObjectMapper objectMapper;
    private final Map<String, SearchJobRecord> cache = new ConcurrentHashMap<String, SearchJobRecord>();

    JobsStore(Path storageRoot, ObjectMapper objectMapper) {
        this.jobsDirectory = storageRoot.resolve("meta").resolve("jobs");
        this.objectMapper = objectMapper;
    }

    SearchJobRecord createJob(JobType type, String repoId, Map<String, Object> payload, String message) {
        String now = CoreUtils.nowIso();
        SearchJobRecord job = new SearchJobRecord();
        job.jobId = CoreUtils.createIdentifier("job");
        job.type = type;
        job.repoId = repoId;
        job.status = JobStatus.queued;
        job.queuePosition = 0;
        job.message = message;
        job.payload = payload;
        job.createdAt = now;
        job.updatedAt = now;
        cache.put(job.jobId, job);
        persist(job);
        return job;
    }

    SearchJobRecord getJob(String jobId) {
        SearchJobRecord cached = cache.get(jobId);
        if (cached != null) {
            return cached;
        }
        Path filePath = jobsDirectory.resolve(jobId + ".json");
        if (!Files.exists(filePath)) {
            throw new AppException("REPOSITORY_NOT_FOUND", "Job was not found.", 404);
        }
        try {
            SearchJobRecord job = objectMapper.readValue(filePath.toFile(), JOB_TYPE);
            cache.put(job.jobId, job);
            return job;
        } catch (IOException error) {
            throw new AppException("INTERNAL_ERROR", "Failed to read job state.", 500, error.getMessage());
        }
    }

    SearchJobRecord updateJob(String jobId, JobStatus status, String message, int queuePosition) {
        return updateJob(jobId, status, message, queuePosition, null, null);
    }

    synchronized SearchJobRecord updateJob(String jobId, JobStatus status, String message, int queuePosition, SearchResult result, ApiModels.AppError error) {
        SearchJobRecord job = getJob(jobId);
        job.status = status;
        job.message = message;
        job.queuePosition = queuePosition;
        if (result != null) {
            job.result = result;
        }
        if (error != null) {
            job.error = error;
            job.result = null;
        }
        job.updatedAt = CoreUtils.nowIso();
        cache.put(jobId, job);
        persist(job);
        return job;
    }

    SearchJobRecord finishJob(String jobId, String message, SearchResult result) {
        SearchJobRecord job = getJob(jobId);
        job.status = JobStatus.done;
        job.message = message;
        job.result = result;
        job.error = null;
        job.updatedAt = CoreUtils.nowIso();
        cache.put(jobId, job);
        persist(job);
        return job;
    }

    SearchJobRecord failJob(String jobId, RuntimeException error, String message) {
        return updateJob(jobId, JobStatus.failed, message, 0, null, AppException.toAppError(error));
    }

    private void persist(SearchJobRecord job) {
        new JsonFileStore<SearchJobRecord>(jobsDirectory.resolve(job.jobId + ".json"), job, JOB_TYPE, objectMapper).write(job);
    }
}
