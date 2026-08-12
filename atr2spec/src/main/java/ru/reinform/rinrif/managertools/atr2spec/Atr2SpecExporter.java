package ru.reinform.rinrif.managertools.atr2spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.reinform.rinrif.managertools.core.JiraReadClient;
import ru.reinform.rinrif.managertools.model.ApiModels.JiraCommentData;
import ru.reinform.rinrif.managertools.model.ApiModels.JiraIssueData;
import ru.reinform.rinrif.managertools.model.ApiModels.JiraIssueLinkData;
import ru.reinform.rinrif.managertools.model.ApiModels.JiraRemoteLinkData;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class Atr2SpecExporter {
    private final Atr2SpecConfig config;
    private final ObjectMapper objectMapper;

    Atr2SpecExporter(Atr2SpecConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        if (!config.verifySsl) {
            disableSslVerification();
        }
    }

    Map<String, Object> exportConfluencePage(String pageId) {
        requireAuth(config.confluenceUser, config.confluenceToken, "Confluence");
        String url = config.confluenceBaseUrl + "/rest/api/content/" + encode(pageId)
                + "?expand=body.storage,version,ancestors,space";
        JsonNode payload = getJson(url, config.confluenceUser, config.confluenceToken);
        String storage = textAt(payload, "body", "storage", "value");
        String text = Atr2SpecTextUtils.htmlToText(storage);
        List<Map<String, String>> ancestors = new ArrayList<Map<String, String>>();
        JsonNode ancestorsNode = payload.get("ancestors");
        if (ancestorsNode != null && ancestorsNode.isArray()) {
            for (JsonNode item : ancestorsNode) {
                Map<String, String> ancestor = new LinkedHashMap<String, String>();
                ancestor.put("id", textAt(item, "id"));
                ancestor.put("title", textAt(item, "title"));
                ancestors.add(ancestor);
            }
        }

        String title = textAt(payload, "title");
        StringBuilder fullText = new StringBuilder(title);
        for (Map<String, String> ancestor : ancestors) {
            fullText.append('\n').append(ancestor.get("title"));
        }
        fullText.append('\n').append(text);

        Map<String, Object> result = Atr2SpecIo.map();
        result.put("source", "confluence");
        result.put("page_id", textAt(payload, "id").isEmpty() ? pageId : textAt(payload, "id"));
        result.put("title", title);
        result.put("url", config.confluenceBaseUrl + "/pages/viewpage.action?pageId=" + pageId);
        result.put("space", textAt(payload, "space", "key"));
        result.put("version", intAt(payload, "version", "number"));
        result.put("ancestors", ancestors);
        result.put("body_storage", storage);
        result.put("text", text);
        result.put("links", Atr2SpecTextUtils.extractUrls(storage));
        result.put("jira_keys", Atr2SpecTextUtils.extractJiraKeys(fullText.toString()));
        result.put("mgsn_keys", Atr2SpecTextUtils.extractMgsnKeys(fullText.toString()));
        result.put("exported_at", Atr2SpecIo.nowIso());
        return result;
    }

    Map<String, Object> exportJiraIssue(String jiraKey) {
        JiraIssueData issue = new JiraReadClient(
                config.jiraBaseUrl, config.jiraUser, config.jiraToken, config.verifySsl, config.httpTimeoutMs, objectMapper
        ).getIssue(jiraKey);
        List<Map<String, String>> comments = new ArrayList<Map<String, String>>();
        for (JiraCommentData item : issue.comments) {
            Map<String, String> comment = new LinkedHashMap<String, String>();
            comment.put("author", item.author);
            comment.put("created", item.created);
            comment.put("body_text", item.body);
            comments.add(comment);
        }
        List<Map<String, String>> remoteLinks = new ArrayList<Map<String, String>>();
        for (JiraRemoteLinkData item : issue.remoteLinks) {
            Map<String, String> link = new LinkedHashMap<String, String>();
            link.put("title", item.title);
            link.put("url", item.url);
            remoteLinks.add(link);
        }
        List<Map<String, String>> issueLinks = new ArrayList<Map<String, String>>();
        for (JiraIssueLinkData item : issue.issueLinks) {
            Map<String, String> link = new LinkedHashMap<String, String>();
            link.put("task_key", item.key);
            link.put("title", item.title);
            link.put("relationship", item.relationship);
            link.put("direction", item.direction);
            link.put("url", item.url);
            issueLinks.add(link);
        }

        StringBuilder textForKeys = new StringBuilder(issue.title).append('\n').append(issue.description);
        for (Map<String, String> comment : comments) {
            textForKeys.append('\n').append(comment.get("body_text"));
        }
        for (Map<String, String> remoteLink : remoteLinks) {
            textForKeys.append('\n').append(remoteLink.get("url"));
        }
        for (Map<String, String> issueLink : issueLinks) {
            textForKeys.append('\n').append(issueLink.get("task_key"));
        }

        Map<String, Object> result = Atr2SpecIo.map();
        result.put("source", "jira");
        result.put("task_key", issue.key);
        result.put("title", issue.title);
        result.put("description_text", issue.description);
        result.put("status", issue.status);
        result.put("issue_type", issue.issueType);
        result.put("project_key", issue.projectKey);
        result.put("labels", issue.labels);
        result.put("components", issue.components);
        result.put("comments", comments);
        result.put("issue_links", issueLinks);
        result.put("remote_links", remoteLinks);
        result.put("source_url", issue.url);
        result.put("jira_keys", Atr2SpecTextUtils.extractJiraKeys(textForKeys.toString()));
        result.put("mgsn_keys", Atr2SpecTextUtils.extractMgsnKeys(textForKeys.toString()));
        result.put("exported_at", Atr2SpecIo.nowIso());
        return result;
    }

    private JsonNode getJson(String url, String user, String token) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(config.httpTimeoutMs);
            connection.setReadTimeout(config.httpTimeoutMs);
            connection.setRequestProperty("Accept", "application/json");
            String auth = user + ":" + token;
            connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8)));
            int status = connection.getResponseCode();
            InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] bytes = readAll(body);
            if (status >= 400) {
                throw new Atr2SpecException("Source request failed with HTTP " + status + ": " + url, status);
            }
            return objectMapper.readTree(bytes);
        } catch (IOException error) {
            throw new Atr2SpecException("Source request failed: " + url, 502, error);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return new byte[0];
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String textAt(JsonNode node, String... path) {
        JsonNode current = node;
        for (String item : path) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return "";
            }
            current = current.get(item);
        }
        if (current == null || current.isMissingNode() || current.isNull()) {
            return "";
        }
        return current.asText("");
    }

    private static int intAt(JsonNode node, String... path) {
        JsonNode current = node;
        for (String item : path) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return 0;
            }
            current = current.get(item);
        }
        return current == null || current.isMissingNode() || current.isNull() ? 0 : current.asInt(0);
    }

    private static void requireAuth(String user, String token, String source) {
        if (user == null || user.isEmpty() || token == null || token.isEmpty()) {
            throw new Atr2SpecException("Missing credentials for " + source + ".", 400);
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception error) {
            return value == null ? "" : value;
        }
    }

    private static String firstFilled(String first, String second) {
        return first != null && !first.trim().isEmpty() ? first : (second == null ? "" : second);
    }

    private static void disableSslVerification() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
        } catch (Exception ignored) {
            // If custom SSL setup fails, the source request will surface the real error.
        }
    }
}
