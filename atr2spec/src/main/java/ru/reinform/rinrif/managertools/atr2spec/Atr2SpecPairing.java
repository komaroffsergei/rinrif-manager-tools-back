package ru.reinform.rinrif.managertools.atr2spec;

import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecModels.Atr2SpecPairCandidate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class Atr2SpecPairing {
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");

    List<Atr2SpecPairCandidate> buildReport(Map<String, Object> source, List<Map<String, Object>> pages, List<Map<String, Object>> jiraItems, int maxCandidates) {
        List<Atr2SpecPairCandidate> candidates = new ArrayList<Atr2SpecPairCandidate>();
        for (Map<String, Object> page : pages) {
            if (text(page, "page_id").equals(text(source, "page_id"))) {
                continue;
            }
            Atr2SpecPairCandidate candidate = scoreCandidate(source, page, jiraItems);
            if (candidate.score > 0) {
                candidates.add(candidate);
            }
        }
        Collections.sort(candidates, new Comparator<Atr2SpecPairCandidate>() {
            @Override
            public int compare(Atr2SpecPairCandidate left, Atr2SpecPairCandidate right) {
                return Integer.compare(right.score, left.score);
            }
        });
        if (candidates.size() <= maxCandidates) {
            return candidates;
        }
        return new ArrayList<Atr2SpecPairCandidate>(candidates.subList(0, maxCandidates));
    }

    private Atr2SpecPairCandidate scoreCandidate(Map<String, Object> source, Map<String, Object> page, List<Map<String, Object>> jiraItems) {
        String sourceBlob = pageText(source);
        String candidateBlob = pageText(page);
        String sourceLower = sourceBlob.toLowerCase(Locale.ROOT);
        String candidateLower = candidateBlob.toLowerCase(Locale.ROOT);
        List<String> sourceMgsn = listOrExtract(source, "mgsn_keys", sourceBlob, true);
        List<String> candidateMgsn = listOrExtract(page, "mgsn_keys", candidateBlob, true);
        Set<String> sourceJira = new LinkedHashSet<String>(listOrExtract(source, "jira_keys", sourceBlob, false));
        Set<String> candidateJira = new LinkedHashSet<String>(listOrExtract(page, "jira_keys", candidateBlob, false));

        Atr2SpecPairCandidate result = new Atr2SpecPairCandidate();
        result.pageId = text(page, "page_id");
        result.title = text(page, "title");
        result.url = text(page, "url");
        result.mgsnKeys = candidateMgsn;
        result.jiraKeys = new ArrayList<String>(candidateJira);

        if (hasAncestor(page, "постановки")) {
            result.score += 25;
            result.reasons.add("candidate_under_postanovki_tree");
        }
        if (candidateLower.contains("формы задач") || text(page, "title").toLowerCase(Locale.ROOT).contains("фз")) {
            result.score += 15;
            result.reasons.add("candidate_looks_like_task_form");
        }
        List<String> sharedJira = shared(sourceJira, candidateJira);
        if (!sharedJira.isEmpty()) {
            result.score += 30;
            result.reasons.add("shared_jira_keys:" + Atr2SpecTextUtils.join(sharedJira, ","));
        }
        List<String> matchedJira = candidateUrlMatchesJira(page, jiraItems);
        if (!matchedJira.isEmpty()) {
            result.score += 35;
            result.reasons.add("jira_remote_link_points_to_candidate:" + Atr2SpecTextUtils.join(matchedJira, ","));
        }
        for (String number : numbers(sourceMgsn)) {
            if (candidateLower.contains(number)) {
                result.score += 20;
                result.reasons.add("candidate_mentions_source_mgsn:" + number);
            }
        }
        for (String number : numbers(candidateMgsn)) {
            if (sourceLower.contains("mgsn-" + number) || sourceLower.contains(" " + number)) {
                result.score += 20;
                result.reasons.add("source_mentions_candidate_mgsn:" + number);
            }
        }
        if (candidateLower.contains("версионность") && candidateLower.contains("изменени")) {
            result.score += 10;
            result.reasons.add("candidate_has_version_history");
        }
        if (candidateLower.contains("изменениями в атр") || candidateLower.contains("с учетом")) {
            result.score += 10;
            result.reasons.add("candidate_mentions_atr_revision");
        }
        return result;
    }

    private static String pageText(Map<String, Object> page) {
        StringBuilder result = new StringBuilder(text(page, "title"));
        Object ancestors = page.get("ancestors");
        if (ancestors instanceof List) {
            for (Object item : (List<?>) ancestors) {
                if (item instanceof Map) {
                    result.append('\n').append(text((Map<?, ?>) item, "title"));
                }
            }
        }
        result.append('\n').append(text(page, "text"));
        return result.toString();
    }

    private static boolean hasAncestor(Map<String, Object> page, String title) {
        Object ancestors = page.get("ancestors");
        if (!(ancestors instanceof List)) {
            return false;
        }
        for (Object item : (List<?>) ancestors) {
            if (item instanceof Map && title.equalsIgnoreCase(text((Map<?, ?>) item, "title"))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> candidateUrlMatchesJira(Map<String, Object> candidate, List<Map<String, Object>> jiraItems) {
        List<String> matched = new ArrayList<String>();
        String pageId = text(candidate, "page_id");
        String url = text(candidate, "url");
        for (Map<String, Object> issue : jiraItems) {
            StringBuilder issueText = new StringBuilder(text(issue, "description_text"));
            Object remoteLinks = issue.get("remote_links");
            if (remoteLinks instanceof List) {
                for (Object item : (List<?>) remoteLinks) {
                    if (item instanceof Map) {
                        issueText.append('\n').append(text((Map<?, ?>) item, "url"));
                        issueText.append('\n').append(text((Map<?, ?>) item, "title"));
                    }
                }
            }
            String blob = issueText.toString();
            if ((!pageId.isEmpty() && blob.contains(pageId)) || (!url.isEmpty() && blob.contains(url))) {
                matched.add(text(issue, "task_key"));
            }
        }
        return matched;
    }

    private static List<String> listOrExtract(Map<String, Object> page, String key, String text, boolean mgsn) {
        Object value = page.get(key);
        if (value instanceof List) {
            List<String> result = new ArrayList<String>();
            for (Object item : (List<?>) value) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return mgsn ? Atr2SpecTextUtils.extractMgsnKeys(text) : Atr2SpecTextUtils.extractJiraKeys(text);
    }

    private static List<String> numbers(List<String> keys) {
        Set<String> result = new LinkedHashSet<String>();
        for (String key : keys) {
            Matcher matcher = NUMBER.matcher(key);
            if (matcher.find()) {
                result.add(matcher.group(1));
            }
        }
        return new ArrayList<String>(result);
    }

    private static List<String> shared(Set<String> left, Set<String> right) {
        List<String> result = new ArrayList<String>();
        for (String item : left) {
            if (right.contains(item)) {
                result.add(item);
            }
        }
        Collections.sort(result);
        return result;
    }

    static String text(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
