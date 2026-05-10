package ru.reinform.rinrif.managertools.atr2spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecModels.Atr2SpecArtifact;
import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecModels.Atr2SpecJobRecord;
import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecModels.Atr2SpecPairCandidate;
import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecModels.Atr2SpecQueuedResponse;
import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecModels.Atr2SpecRunRequest;
import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecModels.Atr2SpecValidationReport;
import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecModels.RunStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Atr2SpecService {
    private static final Pattern PAGE_ID = Pattern.compile("(?:pageId=|pages/(?:viewpage\\.action\\?pageId=)?)(\\d+)|^(\\d+)$");
    private static final Pattern JIRA_KEY = Pattern.compile("\\b([A-Z][A-Z0-9]+-\\d+)\\b");
    private static final int MAX_CANDIDATES = 20;

    private final Atr2SpecConfig config;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public Atr2SpecService() {
        this(null);
    }

    public Atr2SpecService(Map<String, String> externalConfig) {
        this.config = Atr2SpecConfig.load(externalConfig);
        this.objectMapper = new ObjectMapper();
    }

    public Atr2SpecQueuedResponse startRun(final Atr2SpecRunRequest request) {
        final Atr2SpecRunRequest normalizedRequest = normalizeRequest(request);
        final Atr2SpecJobRecord job = createJob(normalizedRequest);
        writeJob(job);
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                execute(job.jobId, normalizedRequest);
            }
        });
        return new Atr2SpecQueuedResponse(job.jobId);
    }

    public Atr2SpecJobRecord getRun(String jobId) {
        Path path = jobDir(jobId).resolve("job.json");
        if (!Files.isRegularFile(path)) {
            throw new Atr2SpecException("ATR2Spec job was not found.", 404);
        }
        return readJob(path);
    }

    public Atr2SpecArtifact getDraft(String jobId) {
        Path path = jobDir(jobId).resolve("draft.md");
        if (!Files.isRegularFile(path)) {
            throw new Atr2SpecException("ATR2Spec draft was not found.", 404);
        }
        return new Atr2SpecArtifact(Atr2SpecIo.readText(path), "text/markdown; charset=UTF-8");
    }

    public Atr2SpecArtifact getArtifact(String jobId, String name) {
        String normalizedName = normalizeArtifactName(name);
        Path path = jobDir(jobId).resolve(normalizedName);
        if (!Files.isRegularFile(path)) {
            throw new Atr2SpecException("ATR2Spec artifact was not found.", 404);
        }
        String mediaType = normalizedName.endsWith(".md") ? "text/markdown; charset=UTF-8" : "application/json; charset=UTF-8";
        return new Atr2SpecArtifact(Atr2SpecIo.readText(path), mediaType);
    }

    private void execute(String jobId, Atr2SpecRunRequest request) {
        Atr2SpecJobRecord job = getRun(jobId);
        try {
            updateJob(job, RunStatus.running, "export", "Exporting Wiki/Jira sources");
            Path dir = jobDir(jobId);
            Path pagesDir = dir.resolve("pages");
            Path jiraDir = dir.resolve("jira");
            Atr2SpecExporter exporter = new Atr2SpecExporter(config, objectMapper);
            List<Map<String, Object>> pages = new ArrayList<Map<String, Object>>();
            List<Map<String, Object>> jiraItems = new ArrayList<Map<String, Object>>();

            Map<String, Object> atrPage = exporter.exportConfluencePage(request.atrPageId);
            pages.add(atrPage);
            writePage(pagesDir, atrPage);
            if (request.specPageId != null && !request.specPageId.trim().isEmpty()) {
                Map<String, Object> specPage = exporter.exportConfluencePage(request.specPageId);
                pages.add(specPage);
                writePage(pagesDir, specPage);
            }
            if (request.jiraKey != null && !request.jiraKey.trim().isEmpty()) {
                Map<String, Object> jira = exporter.exportJiraIssue(request.jiraKey);
                jiraItems.add(jira);
                writeJira(jiraDir, jira);
            }

            Map<String, Object> exportSummary = Atr2SpecIo.map();
            exportSummary.put("job_id", jobId);
            exportSummary.put("atr_page_id", request.atrPageId);
            exportSummary.put("spec_page_id", request.specPageId);
            exportSummary.put("jira_key", request.jiraKey);
            exportSummary.put("pages", summarizePages(pages));
            exportSummary.put("jira", summarizeJira(jiraItems));
            Atr2SpecIo.writeJson(objectMapper, dir.resolve("export-summary.json"), exportSummary);

            updateJob(job, RunStatus.running, "pair", "Scoring постановка candidates");
            Atr2SpecPairing pairing = new Atr2SpecPairing();
            List<Atr2SpecPairCandidate> candidates = pairing.buildReport(atrPage, pages, jiraItems, MAX_CANDIDATES);
            Map<String, Object> pairingReport = Atr2SpecIo.map();
            pairingReport.put("atr_page_id", request.atrPageId);
            pairingReport.put("atr_title", atrPage.get("title"));
            pairingReport.put("jira_key", request.jiraKey);
            pairingReport.put("candidates", candidates);
            Atr2SpecIo.writeJson(objectMapper, dir.resolve("pairing.json"), pairingReport);

            updateJob(job, RunStatus.running, "extract", "Extracting normalized model");
            Map<String, Object> model = new Atr2SpecExtractor().extract(atrPage);
            Atr2SpecIo.writeJson(objectMapper, dir.resolve("model.json"), model);

            updateJob(job, RunStatus.running, "generate", "Generating Confluence markdown draft");
            String draft = new Atr2SpecMarkdownGenerator().render(model);
            Atr2SpecIo.writeText(dir.resolve("draft.md"), draft);

            updateJob(job, RunStatus.running, "validate", "Validating generated draft");
            Atr2SpecValidationReport validation = new Atr2SpecValidator().validate(model, draft, Boolean.TRUE.equals(request.allowTodo));
            Atr2SpecIo.writeJson(objectMapper, dir.resolve("validation.json"), validation);

            job = getRun(jobId);
            job.status = RunStatus.done;
            job.step = "done";
            job.message = validation.ok ? "ATR2Spec draft generated" : "ATR2Spec draft generated with validation errors";
            job.candidates = candidates;
            job.validation = validation;
            job.summary = buildSummary(model, draft, exportSummary, candidates);
            job.updatedAt = Atr2SpecIo.nowIso();
            writeJob(job);
        } catch (RuntimeException error) {
            Atr2SpecJobRecord failedJob = getRun(jobId);
            failedJob.status = RunStatus.failed;
            failedJob.step = "failed";
            failedJob.message = "ATR2Spec pipeline failed";
            failedJob.error = error.getMessage();
            failedJob.updatedAt = Atr2SpecIo.nowIso();
            writeJob(failedJob);
        }
    }

    private Atr2SpecRunRequest normalizeRequest(Atr2SpecRunRequest request) {
        Atr2SpecRunRequest result = request == null ? new Atr2SpecRunRequest() : request;
        result.atrPageId = firstFilled(result.atrPageId, extractPageId(result.atrPage));
        result.specPageId = firstFilled(result.specPageId, extractPageId(result.specPage));
        result.jiraKey = firstFilled(result.jiraKey, extractJiraKey(result.jira));
        if (result.atrPageId == null || result.atrPageId.trim().isEmpty()) {
            throw new Atr2SpecException("ATR pageId is required.", 400);
        }
        if (result.allowTodo == null) {
            result.allowTodo = Boolean.TRUE;
        }
        return result;
    }

    private Atr2SpecJobRecord createJob(Atr2SpecRunRequest request) {
        Atr2SpecJobRecord job = new Atr2SpecJobRecord();
        job.jobId = "atr2spec_" + UUID.randomUUID().toString().replace("-", "");
        job.status = RunStatus.queued;
        job.step = "queued";
        job.message = "ATR2Spec run queued";
        job.request = request;
        job.createdAt = Atr2SpecIo.nowIso();
        job.updatedAt = job.createdAt;
        return job;
    }

    private Map<String, Object> buildSummary(Map<String, Object> model, String draft, Map<String, Object> exportSummary, List<Atr2SpecPairCandidate> candidates) {
        Map<String, Object> summary = Atr2SpecIo.map();
        summary.put("export", exportSummary);
        summary.put("fieldsCount", list(model.get("fields")).size());
        summary.put("actionsCount", list(model.get("actions")).size());
        summary.put("validationsCount", list(model.get("validations")).size());
        summary.put("openQuestionsCount", list(model.get("open_questions")).size());
        summary.put("confidence", model.get("confidence"));
        summary.put("draftLength", draft == null ? 0 : draft.length());
        summary.put("topCandidate", candidates.isEmpty() ? null : candidates.get(0));
        return summary;
    }

    private void updateJob(Atr2SpecJobRecord job, RunStatus status, String step, String message) {
        job.status = status;
        job.step = step;
        job.message = message;
        job.updatedAt = Atr2SpecIo.nowIso();
        writeJob(job);
    }

    private void writeJob(Atr2SpecJobRecord job) {
        Atr2SpecIo.writeJson(objectMapper, jobDir(job.jobId).resolve("job.json"), job);
    }

    private Atr2SpecJobRecord readJob(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), Atr2SpecJobRecord.class);
        } catch (Exception error) {
            throw new Atr2SpecException("Failed to read ATR2Spec job.", 500, error);
        }
    }

    private void writePage(Path pagesDir, Map<String, Object> page) {
        Atr2SpecIo.writeJson(objectMapper, pagesDir.resolve(Atr2SpecPairing.text(page, "page_id") + ".json"), page);
    }

    private void writeJira(Path jiraDir, Map<String, Object> jira) {
        Atr2SpecIo.writeJson(objectMapper, jiraDir.resolve(Atr2SpecPairing.text(jira, "task_key") + ".json"), jira);
    }

    private Path jobDir(String jobId) {
        String safeJobId = jobId == null ? "" : jobId.replaceAll("[^A-Za-z0-9_-]", "");
        if (safeJobId.isEmpty()) {
            throw new Atr2SpecException("ATR2Spec job id is required.", 400);
        }
        return config.atr2SpecRoot.resolve("jobs").resolve(safeJobId);
    }

    private static List<Map<String, Object>> summarizePages(List<Map<String, Object>> pages) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> page : pages) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("page_id", page.get("page_id"));
            item.put("title", page.get("title"));
            item.put("url", page.get("url"));
            result.add(item);
        }
        return result;
    }

    private static List<Map<String, Object>> summarizeJira(List<Map<String, Object>> jiraItems) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> jira : jiraItems) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("task_key", jira.get("task_key"));
            item.put("title", jira.get("title"));
            item.put("source_url", jira.get("source_url"));
            result.add(item);
        }
        return result;
    }

    private static String normalizeArtifactName(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if ("model".equals(normalized)) {
            return "model.json";
        }
        if ("pairing".equals(normalized)) {
            return "pairing.json";
        }
        if ("validation".equals(normalized)) {
            return "validation.json";
        }
        if ("export-summary".equals(normalized)) {
            return "export-summary.json";
        }
        if ("draft".equals(normalized)) {
            return "draft.md";
        }
        throw new Atr2SpecException("Unsupported ATR2Spec artifact name.", 400);
    }

    private static String extractPageId(String value) {
        if (value == null) {
            return "";
        }
        Matcher matcher = PAGE_ID.matcher(value.trim());
        if (matcher.find()) {
            return firstFilled(matcher.group(1), matcher.group(2));
        }
        return "";
    }

    private static String extractJiraKey(String value) {
        if (value == null) {
            return "";
        }
        Matcher matcher = JIRA_KEY.matcher(value.toUpperCase(Locale.ROOT));
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String firstFilled(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static List<?> list(Object value) {
        return value instanceof List ? (List<?>) value : java.util.Collections.emptyList();
    }
}
