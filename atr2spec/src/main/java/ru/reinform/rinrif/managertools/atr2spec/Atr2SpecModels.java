package ru.reinform.rinrif.managertools.atr2spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Atr2SpecModels {
    private Atr2SpecModels() {
    }

    public enum RunStatus {
        queued,
        running,
        done,
        failed
    }

    public static class Atr2SpecRunRequest {
        public String atrPage;
        public String atrPageId;
        public String jira;
        public String jiraKey;
        public String specPage;
        public String specPageId;
        public Boolean allowTodo;
    }

    public static class Atr2SpecQueuedResponse {
        public String jobId;
        public String status = "queued";
        public int queuePosition;

        public Atr2SpecQueuedResponse() {
        }

        public Atr2SpecQueuedResponse(String jobId) {
            this.jobId = jobId;
        }
    }

    public static class Atr2SpecJobRecord {
        public String jobId;
        public RunStatus status;
        public String step;
        public String message;
        public Atr2SpecRunRequest request;
        public Map<String, Object> summary = new LinkedHashMap<String, Object>();
        public List<Atr2SpecPairCandidate> candidates = new ArrayList<Atr2SpecPairCandidate>();
        public Atr2SpecValidationReport validation;
        public String error;
        public String createdAt;
        public String updatedAt;
    }

    public static class Atr2SpecPairCandidate {
        public String pageId;
        public String title;
        public String url;
        public int score;
        public List<String> reasons = new ArrayList<String>();
        public List<String> mgsnKeys = new ArrayList<String>();
        public List<String> jiraKeys = new ArrayList<String>();
    }

    public static class Atr2SpecValidationReport {
        public boolean ok;
        public List<String> errors = new ArrayList<String>();
        public List<String> warnings = new ArrayList<String>();
        public Map<String, Object> reports = new LinkedHashMap<String, Object>();
    }

    public static class Atr2SpecArtifact {
        public String content;
        public String mediaType;

        public Atr2SpecArtifact() {
        }

        public Atr2SpecArtifact(String content, String mediaType) {
            this.content = content;
            this.mediaType = mediaType;
        }
    }
}
