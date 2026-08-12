package ru.reinform.rinrif.managertools.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceApplication;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceRunRecord;
import ru.reinform.rinrif.managertools.model.ApiModels.ReleaseTraceStatus;

import java.nio.file.Files;
import java.nio.file.Path;

public class ReleaseTraceStoreTest {
    @Test
    public void restoresPersistedPartialRunAfterServiceRestart() throws Exception {
        Path storage = Files.createTempDirectory("release-trace-store-");
        ObjectMapper mapper = new ObjectMapper();
        ReleaseTraceStore first = new ReleaseTraceStore(storage, mapper);
        ReleaseTraceRunRecord run = new ReleaseTraceRunRecord();
        run.runId = "release_test";
        run.status = ReleaseTraceStatus.partial;
        run.progress = 100;
        run.createdAt = CoreUtils.nowIso();
        first.write(run);

        ReleaseTraceStore restarted = new ReleaseTraceStore(storage, mapper);
        ReleaseTraceRunRecord restored = restarted.get("release_test");
        Assert.assertEquals(ReleaseTraceStatus.partial, restored.status);
        Assert.assertEquals(100, restored.progress);
        restored.progress = 1;
        Assert.assertEquals(100, restarted.get("release_test").progress);
    }

    @Test
    public void recoversInterruptedRunWithApplicationEvidenceAsPartial() throws Exception {
        Path storage = Files.createTempDirectory("release-trace-store-partial-");
        ObjectMapper mapper = new ObjectMapper();
        ReleaseTraceStore first = new ReleaseTraceStore(storage, mapper);
        ReleaseTraceRunRecord run = new ReleaseTraceRunRecord();
        run.runId = "interrupted_with_data";
        run.status = ReleaseTraceStatus.building_diff;
        run.progress = 72;
        run.step = "building_diff";
        ReleaseTraceApplication application = new ReleaseTraceApplication();
        application.application = "ui-nadzor";
        application.status = "queued";
        run.applications.add(application);
        first.write(run);

        ReleaseTraceRunRecord recovered = new ReleaseTraceStore(storage, mapper).get(run.runId);

        Assert.assertEquals(ReleaseTraceStatus.partial, recovered.status);
        Assert.assertEquals(100, recovered.progress);
        Assert.assertEquals("partial", recovered.step);
        Assert.assertNull(recovered.error);
        Assert.assertEquals("partial", recovered.applications.get(0).status);
        Assert.assertFalse(recovered.warnings.isEmpty());
        Assert.assertEquals(ReleaseTraceStatus.partial,
                new ReleaseTraceStore(storage, mapper).get(run.runId).status);
    }

    @Test
    public void recoversInterruptedRunWithoutApplicationEvidenceAsFailed() throws Exception {
        Path storage = Files.createTempDirectory("release-trace-store-failed-");
        ObjectMapper mapper = new ObjectMapper();
        ReleaseTraceStore first = new ReleaseTraceStore(storage, mapper);
        ReleaseTraceRunRecord run = new ReleaseTraceRunRecord();
        run.runId = "interrupted_without_data";
        run.status = ReleaseTraceStatus.reading_jira;
        run.progress = 8;
        first.write(run);

        ReleaseTraceRunRecord recovered = new ReleaseTraceStore(storage, mapper).get(run.runId);

        Assert.assertEquals(ReleaseTraceStatus.failed, recovered.status);
        Assert.assertEquals(100, recovered.progress);
        Assert.assertEquals("failed", recovered.step);
        Assert.assertNotNull(recovered.error);
        Assert.assertEquals("RELEASE_TRACE_INTERRUPTED", recovered.error.code);
        Assert.assertEquals(ReleaseTraceStatus.failed,
                new ReleaseTraceStore(storage, mapper).get(run.runId).status);
    }
}
