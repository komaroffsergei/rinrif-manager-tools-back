package ru.reinform.rinrif.managertools.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Shared read-only Jira client used by Release Trace and ATR2Spec. */
public class JiraReadClient {
    private final String baseUrl;
    private final String user;
    private final String token;
    private final boolean verifySsl;
    private final int timeoutMs;
    private final ObjectMapper objectMapper;

    public JiraReadClient(String baseUrl, String user, String token, boolean verifySsl, int timeoutMs, ObjectMapper objectMapper) {
        this.baseUrl = trimRight(baseUrl, "/");
        this.user = emptyToNull(user);
        this.token = emptyToNull(token);
        this.verifySsl = verifySsl;
        this.timeoutMs = timeoutMs;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public JiraIssueData getIssue(String inputKey) {
        requireCredentials();
        String key = CoreUtils.safe(inputKey).trim().toUpperCase();
        if (!key.matches("[A-Z][A-Z0-9]+-\\d+")) {
            throw new AppException("INVALID_JIRA_KEY", "Jira issue key is invalid.", 400);
        }
        Map<String, String> headers = basicHeaders(user, token);
        String fields = "summary,description,status,issuetype,project,labels,components,issuelinks";
        JsonNode issue = getJson(
                baseUrl + "/rest/api/2/issue/" + encode(key) + "?fields=" + encode(fields),
                headers
        );
        JsonNode fieldsNode = issue.path("fields");
        JiraIssueData result = new JiraIssueData();
        result.key = firstFilled(textAt(issue, "key"), key);
        result.title = textAt(fieldsNode, "summary");
        result.description = flattenText(fieldsNode.get("description"));
        result.status = textAt(fieldsNode, "status", "name");
        result.issueType = textAt(fieldsNode, "issuetype", "name");
        result.projectKey = textAt(fieldsNode, "project", "key");
        result.url = baseUrl + "/browse/" + result.key;
        appendStrings(result.labels, fieldsNode.get("labels"), null);
        appendStrings(result.components, fieldsNode.get("components"), "name");
        readIssueLinks(result, fieldsNode.get("issuelinks"));
        readComments(result, headers);
        readRemoteLinks(result, headers);
        return result;
    }

    private void readComments(JiraIssueData issue, Map<String, String> headers) {
        int startAt = 0;
        int total = Integer.MAX_VALUE;
        while (startAt < total) {
            JsonNode response = getJson(
                    baseUrl + "/rest/api/2/issue/" + encode(issue.key) + "/comment?startAt=" + startAt + "&maxResults=100",
                    headers
            );
            JsonNode items = response.path("comments");
            int received = 0;
            if (items.isArray()) {
                for (JsonNode item : items) {
                    JiraCommentData comment = new JiraCommentData();
                    comment.author = firstFilled(textAt(item, "author", "displayName"), textAt(item, "author", "name"));
                    comment.created = textAt(item, "created");
                    comment.body = flattenText(item.get("body"));
                    issue.comments.add(comment);
                    received++;
                }
            }
            total = response.path("total").asInt(startAt + received);
            if (received == 0) {
                break;
            }
            startAt += received;
        }
    }

    private void readIssueLinks(JiraIssueData issue, JsonNode links) {
        if (links == null || !links.isArray()) {
            return;
        }
        for (JsonNode item : links) {
            JsonNode linked = item.get("outwardIssue");
            String direction = "outward";
            String relationship = textAt(item, "type", "outward");
            if (linked == null || linked.isNull()) {
                linked = item.get("inwardIssue");
                direction = "inward";
                relationship = textAt(item, "type", "inward");
            }
            String linkedKey = textAt(linked, "key");
            if (linkedKey.isEmpty()) {
                continue;
            }
            JiraIssueLinkData link = new JiraIssueLinkData();
            link.key = linkedKey.toUpperCase();
            link.title = textAt(linked, "fields", "summary");
            link.relationship = relationship;
            link.direction = direction;
            link.url = baseUrl + "/browse/" + link.key;
            issue.issueLinks.add(link);
        }
    }

    private void readRemoteLinks(JiraIssueData issue, Map<String, String> headers) {
        JsonNode response = getJson(
                baseUrl + "/rest/api/2/issue/" + encode(issue.key) + "/remotelink",
                headers
        );
        if (!response.isArray()) {
            return;
        }
        for (JsonNode item : response) {
            JiraRemoteLinkData link = new JiraRemoteLinkData();
            link.title = textAt(item, "object", "title");
            link.url = textAt(item, "object", "url");
            if (!CoreUtils.safe(link.url).trim().isEmpty()) {
                issue.remoteLinks.add(link);
            }
        }
    }

    private void requireCredentials() {
        if (baseUrl.isEmpty() || user == null || token == null) {
            throw new AppException("JIRA_AUTH_MISSING", "Jira read-only credentials are not configured.", 400);
        }
    }

    private JsonNode getJson(String url, Map<String, String> headers) {
        try {
            return ReadOnlyJsonClient.getJson(url, headers, verifySsl, timeoutMs, objectMapper, "JIRA_REQUEST_FAILED");
        } catch (AppException error) {
            throw mapJiraError(error);
        }
    }

    static AppException mapJiraError(AppException error) {
        if ("AUTH_FAILED".equals(error.getCode()) || error.getStatusCode() == 401 || error.getStatusCode() == 403) {
            return new AppException("JIRA_AUTH_FAILED", "Jira authentication failed.", 401);
        }
        if (error.getStatusCode() == 404) {
            return new AppException("JIRA_NOT_FOUND", "Jira issue was not found.", 404);
        }
        return new AppException("JIRA_UNAVAILABLE", "Jira is temporarily unavailable.", 502);
    }

    public static String flattenText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return node.asText("");
        }
        List<String> values = new ArrayList<String>();
        collectText(node, values);
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            String trimmed = CoreUtils.safe(value).trim();
            if (!trimmed.isEmpty()) {
                if (result.length() > 0) {
                    result.append('\n');
                }
                result.append(trimmed);
            }
        }
        return result.toString();
    }

    private static void collectText(JsonNode node, List<String> values) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            values.add(node.asText(""));
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectText(child, values);
            }
            return;
        }
        JsonNode text = node.get("text");
        if (text != null && text.isValueNode()) {
            values.add(text.asText(""));
            return;
        }
        JsonNode content = node.get("content");
        if (content != null) {
            collectText(content, values);
            return;
        }
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            if (!"type".equals(name) && !"attrs".equals(name) && !"marks".equals(name)
                    && !"id".equals(name) && !"version".equals(name)) {
                collectText(field.getValue(), values);
            }
        }
    }

    static Map<String, String> basicHeaders(String user, String token) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        String auth = CoreUtils.safe(user) + ":" + CoreUtils.safe(token);
        headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8)));
        return headers;
    }

    private static void appendStrings(List<String> target, JsonNode values, String objectField) {
        if (values == null || !values.isArray()) {
            return;
        }
        for (JsonNode item : values) {
            String value = objectField == null ? item.asText("") : textAt(item, objectField);
            if (!value.trim().isEmpty()) {
                target.add(value);
            }
        }
    }

    static String textAt(JsonNode node, String... path) {
        JsonNode current = node;
        for (String item : path) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return "";
            }
            current = current.get(item);
        }
        return current == null || current.isMissingNode() || current.isNull() ? "" : current.asText("");
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(CoreUtils.safe(value), "UTF-8").replace("+", "%20");
        } catch (Exception error) {
            return CoreUtils.safe(value);
        }
    }

    private static String firstFilled(String first, String second) {
        return first != null && !first.trim().isEmpty() ? first : CoreUtils.safe(second);
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String trimRight(String value, String suffix) {
        String result = CoreUtils.safe(value).trim();
        while (result.endsWith(suffix)) {
            result = result.substring(0, result.length() - suffix.length());
        }
        return result;
    }
}

class ReadOnlyJsonClient {
    private static final javax.net.ssl.SSLSocketFactory TRUST_ALL_FACTORY = createTrustAllFactory();
    private static final HostnameVerifier TRUST_ALL_HOSTS = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    static JsonNode getJson(String url, Map<String, String> headers, boolean verifySsl, int timeoutMs,
                            ObjectMapper objectMapper, String errorCode) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            if (!verifySsl && connection instanceof HttpsURLConnection) {
                HttpsURLConnection secure = (HttpsURLConnection) connection;
                secure.setSSLSocketFactory(TRUST_ALL_FACTORY);
                secure.setHostnameVerifier(TRUST_ALL_HOSTS);
            }
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestProperty("Accept", "application/json");
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            int status = connection.getResponseCode();
            InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] bytes = readAll(body);
            if (status >= 400) {
                String code = status == 401 || status == 403 ? "AUTH_FAILED" : errorCode;
                throw new AppException(code, "Read-only source request failed with HTTP " + status + ".", status);
            }
            return bytes.length == 0 ? objectMapper.createArrayNode() : objectMapper.readTree(bytes);
        } catch (AppException error) {
            throw error;
        } catch (IOException error) {
            throw new AppException(errorCode, "Read-only source request failed.", 502, error.getMessage());
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

    private static javax.net.ssl.SSLSocketFactory createTrustAllFactory() {
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
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
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            return context.getSocketFactory();
        } catch (Exception error) {
            return (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
        }
    }
}
