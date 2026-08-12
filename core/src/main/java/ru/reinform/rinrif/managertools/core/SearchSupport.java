package ru.reinform.rinrif.managertools.core;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class SearchQuery {
    final List<String> taskIds;
    final List<String> terms;

    SearchQuery(List<String> taskIds, List<String> terms) {
        this.taskIds = taskIds;
        this.terms = terms;
    }
}

class SearchQueryParser {
    private static final Pattern TASK_ID_PATTERN = Pattern.compile("\\b[A-Za-z][A-Za-z0-9]+-\\d+\\b");
    private static final Pattern TERM_SPLIT_PATTERN = Pattern.compile("[^\\p{L}\\p{N}_/-]+", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    static SearchQuery parse(String input) {
        String original = CoreUtils.safe(input).trim();
        List<String> taskIds = new ArrayList<String>();
        Matcher matcher = TASK_ID_PATTERN.matcher(original);
        while (matcher.find()) {
            addUnique(taskIds, matcher.group().toUpperCase(Locale.ROOT));
        }

        String termsSource = matcher.replaceAll(" ").toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<String>();
        for (String value : TERM_SPLIT_PATTERN.split(termsSource)) {
            String term = value.trim();
            if (term.length() >= 2) {
                addUnique(terms, term);
            }
        }
        return new SearchQuery(taskIds, terms);
    }

    private static void addUnique(List<String> values, String value) {
        if (!values.contains(value)) {
            values.add(value);
        }
    }
}

class CommitMatcher {
    static CommitMatch match(CommitRecord commit, SearchQuery query) {
        String subject = CoreUtils.safe(commit.subject).toLowerCase(Locale.ROOT);
        String body = CoreUtils.safe(commit.body).toLowerCase(Locale.ROOT);
        List<String> taskIdMatches = new ArrayList<String>();
        for (String taskId : query.taskIds) {
            String lowered = taskId.toLowerCase(Locale.ROOT);
            if (subject.contains(lowered) || body.contains(lowered)) {
                taskIdMatches.add(taskId);
            }
        }
        if (!query.taskIds.isEmpty() && taskIdMatches.isEmpty()) {
            return null;
        }

        List<String> subjectTermHits = new ArrayList<String>();
        for (String term : query.terms) {
            if (subject.contains(term)) {
                subjectTermHits.add(term);
            }
        }

        List<String> bodyTermHits = new ArrayList<String>();
        for (String term : query.terms) {
            if (!subjectTermHits.contains(term) && body.contains(term)) {
                bodyTermHits.add(term);
            }
        }

        if (query.taskIds.isEmpty() && subjectTermHits.isEmpty() && bodyTermHits.isEmpty()) {
            return null;
        }
        return new CommitMatch(commit, taskIdMatches, subjectTermHits, bodyTermHits, 0);
    }
}

class CommitRanker {
    static CommitMatch rank(CommitMatch match) {
        match.score = match.taskIdMatches.size() * 100 + match.subjectTermHits.size() * 10 + match.bodyTermHits.size() * 5;
        return match;
    }
}

class CommitMatch {
    final CommitRecord commit;
    final List<String> taskIdMatches;
    final List<String> subjectTermHits;
    final List<String> bodyTermHits;
    int score;

    CommitMatch(CommitRecord commit, List<String> taskIdMatches, List<String> subjectTermHits, List<String> bodyTermHits, int score) {
        this.commit = commit;
        this.taskIdMatches = taskIdMatches;
        this.subjectTermHits = subjectTermHits;
        this.bodyTermHits = bodyTermHits;
        this.score = score;
    }
}

class DiffRangeParser {
    private static final Pattern HUNK_PATTERN = Pattern.compile("\\+(\\d+)(?:,(\\d+))?");

    static List<FileDiffRange> parse(String diffText) {
        List<FileDiffRange> files = new ArrayList<FileDiffRange>();
        FileDiffRange current = null;
        boolean sawHunk = false;

        for (String line : CoreUtils.safe(diffText).split("\\r?\\n")) {
            if (line.startsWith("diff --git ")) {
                current = flush(files, current, sawHunk);
                sawHunk = false;
                current = new FileDiffRange(parsePathFromDiffHeader(line));
                continue;
            }
            if (current == null) {
                continue;
            }
            if (line.startsWith("rename to ")) {
                current.path = decodeGitPath(line.substring("rename to ".length()).trim());
                continue;
            }
            if (line.startsWith("+++ ") && !line.equals("+++ /dev/null")) {
                current.path = stripSidePrefix(decodeGitPath(line.substring("+++ ".length()).trim()), "b/");
                continue;
            }
            if (line.startsWith("Binary files ") || line.startsWith("GIT binary patch") || line.startsWith("Binary file ")) {
                current.fallbackReason = "binary";
                continue;
            }
            if (line.startsWith("@@ ")) {
                sawHunk = true;
                LineRange range = parseUnifiedHunk(line);
                if (range == null) {
                    current.fallbackReason = "parse_failed";
                } else if (range.endLine >= range.startLine) {
                    current.ranges.add(range);
                } else if (current.fallbackReason == null) {
                    current.fallbackReason = "diff_unavailable";
                }
            }
        }

        flush(files, current, sawHunk);
        List<FileDiffRange> result = new ArrayList<FileDiffRange>();
        for (FileDiffRange file : files) {
            if (!CoreUtils.safe(file.path).isEmpty()) {
                result.add(file);
            }
        }
        return result;
    }

    private static FileDiffRange flush(List<FileDiffRange> files, FileDiffRange current, boolean sawHunk) {
        if (current != null) {
            if (current.ranges.isEmpty() && current.fallbackReason == null) {
                current.fallbackReason = sawHunk ? "diff_unavailable" : "parse_failed";
            }
            files.add(current);
        }
        return null;
    }

    private static String parsePathFromDiffHeader(String line) {
        List<String> paths = splitGitPathTokens(line.substring("diff --git ".length()));
        String rawPath = paths.size() > 1 ? paths.get(1) : "";
        return stripSidePrefix(decodeGitPath(rawPath), "b/");
    }

    private static List<String> splitGitPathTokens(String value) {
        List<String> result = new ArrayList<String>();
        int index = 0;
        while (index < value.length()) {
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
                index++;
            }
            if (index >= value.length()) {
                break;
            }
            int start = index;
            if (value.charAt(index) == '"') {
                index++;
                boolean escaped = false;
                while (index < value.length()) {
                    char current = value.charAt(index++);
                    if (current == '"' && !escaped) {
                        break;
                    }
                    if (current == '\\' && !escaped) {
                        escaped = true;
                    } else {
                        escaped = false;
                    }
                }
            } else {
                while (index < value.length() && !Character.isWhitespace(value.charAt(index))) {
                    index++;
                }
            }
            result.add(value.substring(start, index));
        }
        return result;
    }

    /** Decodes Git's core.quotePath C-style quoting, including octal UTF-8 bytes. */
    static String decodeGitPath(String rawPath) {
        String value = CoreUtils.safe(rawPath).trim();
        if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return value;
        }
        String body = value.substring(1, value.length() - 1);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(body.length());
        int index = 0;
        while (index < body.length()) {
            int slash = body.indexOf('\\', index);
            if (slash < 0) {
                writeUtf8(decoded, body.substring(index));
                break;
            }
            writeUtf8(decoded, body.substring(index, slash));
            index = slash + 1;
            if (index >= body.length()) {
                decoded.write('\\');
                break;
            }
            char escaped = body.charAt(index++);
            if (escaped >= '0' && escaped <= '7') {
                int octal = escaped - '0';
                int digits = 1;
                while (digits < 3 && index < body.length()) {
                    char next = body.charAt(index);
                    if (next < '0' || next > '7') {
                        break;
                    }
                    octal = (octal * 8) + (next - '0');
                    index++;
                    digits++;
                }
                decoded.write(octal & 0xff);
                continue;
            }
            switch (escaped) {
                case 'a': decoded.write(7); break;
                case 'b': decoded.write('\b'); break;
                case 't': decoded.write('\t'); break;
                case 'n': decoded.write('\n'); break;
                case 'v': decoded.write(11); break;
                case 'f': decoded.write('\f'); break;
                case 'r': decoded.write('\r'); break;
                case '\\': decoded.write('\\'); break;
                case '"': decoded.write('"'); break;
                default: writeUtf8(decoded, String.valueOf(escaped)); break;
            }
        }
        return new String(decoded.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void writeUtf8(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(bytes, 0, bytes.length);
    }

    private static String stripSidePrefix(String path, String prefix) {
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    private static LineRange parseUnifiedHunk(String line) {
        Matcher matcher = HUNK_PATTERN.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        int startLine = Integer.parseInt(matcher.group(1));
        int count = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
        if (count == 0) {
            return new LineRange(startLine, startLine - 1);
        }
        return new LineRange(startLine, startLine + count - 1);
    }
}

class FileDiffRange {
    String path;
    final List<LineRange> ranges = new ArrayList<LineRange>();
    String fallbackReason;

    FileDiffRange(String path) {
        this.path = path;
    }
}

class ExcludedFilePatterns {
    final List<String> values;

    private ExcludedFilePatterns(List<String> values) {
        this.values = values;
    }

    static ExcludedFilePatterns from(List<String> input) {
        Set<String> unique = new LinkedHashSet<String>();
        for (String value : input == null ? new ArrayList<String>() : input) {
            String normalized = CoreUtils.safe(value).trim();
            if (!normalized.isEmpty()) {
                unique.add(normalized);
            }
        }
        return new ExcludedFilePatterns(new ArrayList<String>(unique));
    }

    boolean isExcluded(String path) {
        String normalizedPath = normalizePath(path);
        if (normalizedPath.isEmpty()) {
            return false;
        }
        String basename = extractBasename(normalizedPath);
        for (String pattern : values) {
            String candidate = pattern.indexOf('/') >= 0 ? normalizedPath : basename;
            if (GlobMatcher.matches(candidate, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizePath(String path) {
        return CoreUtils.safe(path).replace('\\', '/');
    }

    private static String extractBasename(String path) {
        int separator = path.lastIndexOf('/');
        return separator >= 0 ? path.substring(separator + 1) : path;
    }
}

class GlobMatcher {
    static boolean matches(String value, String pattern) {
        return toRegex(pattern).matcher(CoreUtils.safe(value)).matches();
    }

    private static Pattern toRegex(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        String source = CoreUtils.safe(pattern);
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '*') {
                boolean doubleStar = i + 1 < source.length() && source.charAt(i + 1) == '*';
                if (doubleStar) {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
                continue;
            }
            if ("\\.[]{}()+-^$?|".indexOf(current) >= 0) {
                regex.append('\\');
            }
            regex.append(current);
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }
}

class LineRange {
    final int startLine;
    final int endLine;

    LineRange(int startLine, int endLine) {
        this.startLine = startLine;
        this.endLine = endLine;
    }
}

class GitLabUrlBuilder {
    String buildCommitUrl(ru.reinform.rinrif.managertools.model.ApiModels.RepositoryRecord repository, String sha) {
        return repository.normalizedUrl + "/-/commit/" + sha;
    }

    String buildFileUrl(ru.reinform.rinrif.managertools.model.ApiModels.RepositoryRecord repository, String sha, String filePath) {
        return repository.normalizedUrl + "/-/blob/" + sha + "/" + encodePath(filePath);
    }

    String buildLineRangeUrl(ru.reinform.rinrif.managertools.model.ApiModels.RepositoryRecord repository, String sha, String filePath, int startLine, int endLine) {
        String suffix = startLine == endLine ? "#L" + startLine : "#L" + startLine + "-" + endLine;
        return buildFileUrl(repository, sha, filePath) + suffix;
    }

    private String encodePath(String filePath) {
        String[] segments = CoreUtils.safe(filePath).split("/");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                result.append('/');
            }
            result.append(urlEncode(segments[i]));
        }
        return result.toString();
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception error) {
            return value;
        }
    }
}
