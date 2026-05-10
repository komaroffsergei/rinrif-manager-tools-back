package ru.reinform.rinrif.managertools.atr2spec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class Atr2SpecExtractor {
    private static final Set<String> FIELD_TYPE_WORDS = new LinkedHashSet<String>(Arrays.asList(
            "Дата", "Строка", "Лейбл", "Текст", "Кнопка", "Модальное окно", "Дроп-зона",
            "Чек-бокс", "Справочник", "Файл", "Иконка", "Заголовок блока"
    ));
    private static final Set<String> HEADER_WORDS = new LinkedHashSet<String>(Arrays.asList(
            "поле", "наименование", "описание", "тип", "обязательность", "множественность",
            "начальное значение", "чем заполнять", "куда сохранять"
    ));

    Map<String, Object> extract(Map<String, Object> page) {
        String pageId = Atr2SpecPairing.text(page, "page_id");
        String text = Atr2SpecPairing.text(page, "text");
        List<String> lines = nonEmptyLines(text);
        String fullText = Atr2SpecPairing.text(page, "title") + "\n" + text;
        List<Map<String, Object>> tableFields = extractTableFields(pageId, lines);
        List<Map<String, Object>> fields = extractFreeformFields(pageId, lines, tableFields);
        Map<String, String> process = detectProcess(lines);
        Map<String, String> taskForm = detectTaskForm(lines);
        List<Map<String, Object>> openQuestions = extractKeywordItems(pageId, lines, "???", "todo", "требует подтверждения", "правильно указано");
        if (taskForm.get("name").isEmpty()) {
            Map<String, Object> question = Atr2SpecIo.map();
            question.put("text", "TODO: наименование формы задачи требует подтверждения");
            question.put("source_refs", list("page:" + pageId));
            openQuestions.add(question);
        }

        double confidence = 0.35;
        if (!fields.isEmpty()) {
            confidence += Math.min(fields.size(), 8) * 0.05;
        }
        if (!extractActions(pageId, lines).isEmpty()) {
            confidence += 0.1;
        }
        if (!process.get("code").isEmpty() || !process.get("name").isEmpty()) {
            confidence += 0.1;
        }
        if (!taskForm.get("name").isEmpty()) {
            confidence += 0.1;
        }

        Map<String, Object> sourceRef = Atr2SpecIo.map();
        sourceRef.put("source", "confluence");
        sourceRef.put("page_id", pageId);
        sourceRef.put("title", Atr2SpecPairing.text(page, "title"));
        sourceRef.put("url", Atr2SpecPairing.text(page, "url"));
        sourceRef.put("version", page.get("version") == null ? 0 : page.get("version"));

        Map<String, Object> target = Atr2SpecIo.map();
        target.put("kind", "task_form");
        target.put("title", Atr2SpecPairing.text(page, "title"));
        target.put("mgsn_keys", page.get("mgsn_keys") == null ? Atr2SpecTextUtils.extractMgsnKeys(fullText) : page.get("mgsn_keys"));
        target.put("jira_keys", page.get("jira_keys") == null ? Atr2SpecTextUtils.extractJiraKeys(fullText) : page.get("jira_keys"));

        List<Map<String, Object>> bindings = new ArrayList<Map<String, Object>>();
        for (String xpath : Atr2SpecTextUtils.extractXpaths(fullText)) {
            Map<String, Object> binding = Atr2SpecIo.map();
            binding.put("xpath", xpath);
            binding.put("source_refs", list("page:" + pageId));
            bindings.add(binding);
        }

        Map<String, Object> model = Atr2SpecIo.map();
        model.put("schema_version", 1);
        model.put("generated_at", Atr2SpecIo.nowIso());
        model.put("source_refs", list(sourceRef));
        model.put("target_artifact", target);
        model.put("process", process);
        model.put("task_form", taskForm);
        model.put("fields", fields);
        model.put("actions", extractActions(pageId, lines));
        model.put("validations", extractKeywordItems(pageId, lines, "провер", "обязатель", "запрет", "если ", "отображается если"));
        model.put("bindings", bindings);
        model.put("roles", extractKeywordItems(pageId, lines, "пользователь", "автоматически", "ответственный", "начальник", "ldap"));
        model.put("services", extractKeywordItems(pageId, lines, "сервис"));
        model.put("dictionaries", extractKeywordItems(pageId, lines, "справочник"));
        model.put("open_questions", openQuestions);
        model.put("confidence", Math.round(Math.min(confidence, 0.95) * 100.0) / 100.0);
        return model;
    }

    private List<Map<String, Object>> extractTableFields(String pageId, List<String> lines) {
        List<Map<String, Object>> fields = new ArrayList<Map<String, Object>>();
        List<String> headers = new ArrayList<String>();
        List<String> headerBuffer = new ArrayList<String>();
        String currentBlock = "";
        boolean inFormSection = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            inFormSection = updateFormSection(inFormSection, line);
            if (!inFormSection) {
                continue;
            }
            if (isHeaderWord(line)) {
                headerBuffer.add(line);
                if (isFieldHeader(headerBuffer)) {
                    headers = normalizeHeaders(headerBuffer);
                }
                continue;
            }
            if (line.toLowerCase(Locale.ROOT).startsWith("данная форма задачи должна содержать")) {
                headerBuffer.clear();
                continue;
            }
            List<String> cells = Atr2SpecTextUtils.splitTableLine(line);
            if (cells.isEmpty()) {
                headerBuffer.clear();
                Matcher block = Pattern.compile("(?:Блок|Раздел)\\s+\"([^\"]+)\"").matcher(line);
                if (block.find()) {
                    currentBlock = block.group(1);
                    fields.add(buildField(pageId, index, currentBlock, "Заголовок блока.", "Заголовок блока", line, fields, null, null, "", "", ""));
                }
                continue;
            }
            if (isFieldHeader(cells)) {
                headers = normalizeHeaders(cells);
                headerBuffer.clear();
                continue;
            }
            if (headers.isEmpty()) {
                headerBuffer.clear();
                continue;
            }
            headerBuffer.clear();
            String name = valueByHeader(cells, headers, "name");
            String description = valueByHeader(cells, headers, "description");
            String fieldType = valueByHeader(cells, headers, "type");
            if (name.isEmpty() && description.isEmpty()) {
                continue;
            }
            String raw = Atr2SpecTextUtils.join(cells, " ");
            Map<String, Object> field = buildField(
                    pageId,
                    index,
                    name,
                    description,
                    fieldType,
                    raw,
                    fields,
                    Atr2SpecTextUtils.normalizeBool(valueByHeader(cells, headers, "required")),
                    Atr2SpecTextUtils.normalizeBool(valueByHeader(cells, headers, "repeatable")),
                    valueByHeader(cells, headers, "initial"),
                    valueByHeader(cells, headers, "fill_from"),
                    valueByHeader(cells, headers, "save_to")
            );
            if (!currentBlock.isEmpty()) {
                field.put("block", currentBlock);
            }
            fields.add(field);
        }
        return fields;
    }

    private List<Map<String, Object>> extractFreeformFields(String pageId, List<String> lines, List<Map<String, Object>> existingFields) {
        List<Map<String, Object>> fields = new ArrayList<Map<String, Object>>(existingFields);
        String typePattern = typePattern();
        Pattern pattern = Pattern.compile("^(.{1,90}?)\\s+(.{1,220}?)\\s+(" + typePattern + ")(?:\\s+(Да|Нет|-))?(?:\\s+(Да|Нет|-))?");
        boolean inFormSection = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            inFormSection = updateFormSection(inFormSection, line);
            if (!inFormSection || line.contains("|") || line.length() > 420 || hasSource(fields, lineSource(pageId, index))) {
                continue;
            }
            Matcher match = pattern.matcher(line);
            if (!match.find()) {
                continue;
            }
            String name = match.group(1);
            String description = match.group(2);
            String fieldType = match.group(3);
            if (name.toLowerCase(Locale.ROOT).startsWith("раздел") || name.toLowerCase(Locale.ROOT).startsWith("блок")) {
                fieldType = "Заголовок блока";
            }
            fields.add(buildField(
                    pageId,
                    index,
                    name,
                    description,
                    fieldType,
                    line,
                    fields,
                    Atr2SpecTextUtils.normalizeBool(match.group(4) == null ? "" : match.group(4)),
                    Atr2SpecTextUtils.normalizeBool(match.group(5) == null ? "" : match.group(5)),
                    "",
                    "",
                    ""
            ));
        }
        return fields;
    }

    private Map<String, Object> buildField(String pageId, int rowIndex, String name, String description, String fieldType, String raw,
                                           List<Map<String, Object>> existingFields, Boolean required, Boolean repeatable,
                                           String initialValue, String fillFrom, String saveTo) {
        List<String> xpaths = Atr2SpecTextUtils.extractXpaths(raw);
        Map<String, Object> field = Atr2SpecIo.map();
        field.put("index", existingFields.size() + 1);
        field.put("name", isFilled(name) ? name.trim() : "TODO: наименование поля требует подтверждения");
        field.put("type", isFilled(fieldType) ? fieldType.trim() : "TODO: тип требует подтверждения");
        field.put("description", description == null ? "" : description.trim());
        field.put("required", required);
        field.put("repeatable", repeatable);
        field.put("initial_value", initialValue == null ? "" : initialValue.trim());
        field.put("fill_from", isFilled(fillFrom) ? fillFrom.trim() : Atr2SpecTextUtils.join(xpaths, " "));
        field.put("save_to", isFilled(saveTo) ? saveTo.trim() : Atr2SpecTextUtils.join(xpaths, " "));
        field.put("xpaths", xpaths);
        field.put("source_refs", list(lineSource(pageId, rowIndex)));
        return field;
    }

    private Map<String, String> detectProcess(List<String> lines) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        result.put("code", "");
        result.put("name", "");
        result.put("variables", "");
        for (String line : lines) {
            if (line.startsWith("Процесс:")) {
                result.put("code", afterColon(line));
            } else if (line.startsWith("Наименование процесса:")) {
                result.put("name", afterColon(line));
            } else if (line.startsWith("Переменные процесса:")) {
                result.put("variables", afterColon(line));
            }
        }
        return result;
    }

    private Map<String, String> detectTaskForm(List<String> lines) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        result.put("name", "");
        result.put("form_key", "TODO: ключ формы требует подтверждения");
        result.put("url", "");
        Pattern formName = Pattern.compile("(?:Интерактивная форма|Форма задачи)\\s+\"([^\"]+)\"");
        for (String line : lines) {
            if (line.startsWith("Наименование задачи:")) {
                result.put("name", afterColon(line));
            } else if (line.startsWith("Ключ формы:")) {
                String value = afterColon(line);
                if (!value.isEmpty()) {
                    result.put("form_key", value);
                }
            } else if (line.startsWith("Ссылка на форму:")) {
                result.put("url", afterColon(line));
            } else if (result.get("name").isEmpty()) {
                Matcher match = formName.matcher(line);
                if (match.find()) {
                    result.put("name", match.group(1));
                }
            }
        }
        return result;
    }

    private List<Map<String, Object>> extractActions(String pageId, List<String> lines) {
        List<Map<String, Object>> actions = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            String lowered = line.toLowerCase(Locale.ROOT);
            if (!lowered.contains("кнопк") && !lowered.contains("при нажатии") && !lowered.contains("осуществляется переход")) {
                continue;
            }
            Map<String, Object> action = Atr2SpecIo.map();
            action.put("name", Atr2SpecTextUtils.compact(line, 120));
            action.put("description", line);
            action.put("source_refs", list(lineSource(pageId, index)));
            actions.add(action);
        }
        return actions;
    }

    private List<Map<String, Object>> extractKeywordItems(String pageId, List<String> lines, String... words) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            String lowered = line.toLowerCase(Locale.ROOT);
            for (String word : words) {
                if (lowered.contains(word.toLowerCase(Locale.ROOT))) {
                    Map<String, Object> item = Atr2SpecIo.map();
                    item.put("text", line);
                    item.put("source_refs", list(lineSource(pageId, index)));
                    items.add(item);
                    break;
                }
            }
        }
        return items;
    }

    private static boolean updateFormSection(boolean active, String line) {
        String lowered = line.toLowerCase(Locale.ROOT);
        if (line.matches("^1\\.5(?:\\.|\\s).*") || lowered.contains("описание форм задач") || lowered.contains("атрибуты формы")) {
            return true;
        }
        return active && line.matches("^(?:1\\.6|2\\.).*") ? false : active;
    }

    private static String normalizeHeader(String value) {
        String lowered = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (lowered.contains("поле") || lowered.contains("наименование")) {
            return "name";
        }
        if (lowered.contains("опис") || lowered.contains("огранич") || lowered.contains("действ")) {
            return "description";
        }
        if (lowered.contains("тип")) {
            return "type";
        }
        if (lowered.contains("обяз")) {
            return "required";
        }
        if (lowered.contains("множеств")) {
            return "repeatable";
        }
        if (lowered.contains("началь")) {
            return "initial";
        }
        if (lowered.contains("чем заполнять")) {
            return "fill_from";
        }
        if (lowered.contains("куда сохранять")) {
            return "save_to";
        }
        return lowered;
    }

    private static boolean isFieldHeader(List<String> cells) {
        Set<String> normalized = new LinkedHashSet<String>();
        for (String cell : cells) {
            normalized.add(normalizeHeader(cell));
        }
        return normalized.contains("name") && (normalized.contains("description") || normalized.contains("type"));
    }

    private static boolean isHeaderWord(String line) {
        return HEADER_WORDS.contains(line.trim().toLowerCase(Locale.ROOT));
    }

    private static List<String> normalizeHeaders(List<String> cells) {
        List<String> headers = new ArrayList<String>();
        for (String cell : cells) {
            headers.add(normalizeHeader(cell));
        }
        return headers;
    }

    private static String valueByHeader(List<String> row, List<String> headers, String name) {
        for (int index = 0; index < headers.size(); index++) {
            if (name.equals(headers.get(index)) && index < row.size()) {
                return row.get(index);
            }
        }
        return "";
    }

    private static List<String> nonEmptyLines(String text) {
        List<String> result = new ArrayList<String>();
        for (String rawLine : (text == null ? "" : text).split("\\n")) {
            String line = rawLine.trim();
            if (!line.isEmpty()) {
                result.add(line);
            }
        }
        return result;
    }

    private static boolean hasSource(List<Map<String, Object>> fields, String sourceRef) {
        for (Map<String, Object> field : fields) {
            Object refs = field.get("source_refs");
            if (refs instanceof List && ((List<?>) refs).contains(sourceRef)) {
                return true;
            }
        }
        return false;
    }

    private static String lineSource(String pageId, int index) {
        return "page:" + pageId + ":line:" + (index + 1);
    }

    private static String afterColon(String line) {
        int index = line.indexOf(':');
        return index < 0 ? "" : line.substring(index + 1).trim();
    }

    private static boolean isFilled(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String typePattern() {
        List<String> escaped = new ArrayList<String>();
        for (String type : FIELD_TYPE_WORDS) {
            escaped.add(Pattern.quote(type));
        }
        return Atr2SpecTextUtils.join(escaped, "|");
    }

    private static <T> List<T> list(T value) {
        List<T> result = new ArrayList<T>();
        result.add(value);
        return result;
    }
}
