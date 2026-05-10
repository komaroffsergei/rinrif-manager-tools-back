package ru.reinform.rinrif.managertools.atr2spec;

import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecModels.Atr2SpecValidationReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

class Atr2SpecValidator {
    private static final String[] REQUIRED_DRAFT_SECTIONS = new String[]{
            "Версионность",
            "Связанная задача",
            "Спецификация",
            "Постановка",
            "Атрибуты формы",
            "Кнопки"
    };

    Atr2SpecValidationReport validate(Map<String, Object> model, String draft, boolean allowTodo) {
        Atr2SpecValidationReport result = new Atr2SpecValidationReport();
        Map<String, Object> modelReport = validateModel(model, allowTodo);
        Map<String, Object> draftReport = validateDraft(draft, allowTodo);
        result.reports.put("model", modelReport);
        result.reports.put("draft", draftReport);
        result.errors.addAll(strings(modelReport.get("errors")));
        result.errors.addAll(strings(draftReport.get("errors")));
        result.warnings.addAll(strings(modelReport.get("warnings")));
        result.warnings.addAll(strings(draftReport.get("warnings")));
        result.ok = result.errors.isEmpty();
        return result;
    }

    private Map<String, Object> validateModel(Map<String, Object> model, boolean allowTodo) {
        List<String> errors = new ArrayList<String>();
        List<String> warnings = new ArrayList<String>();
        if (list(model.get("source_refs")).isEmpty()) {
            errors.add("model.source_refs is required");
        }
        if (text(map(model.get("target_artifact")), "title").isEmpty()) {
            errors.add("model.target_artifact.title is required");
        }
        List<?> fields = list(model.get("fields"));
        if (fields.isEmpty()) {
            errors.add("model.fields must contain at least one field");
        }
        int index = 1;
        for (Object value : fields) {
            Map<?, ?> field = map(value);
            if (list(field.get("source_refs")).isEmpty()) {
                errors.add("field[" + index + "] has no source_refs");
            }
            if (!list(field.get("xpaths")).isEmpty() && list(field.get("source_refs")).isEmpty()) {
                errors.add("field[" + index + "] has xpaths without source_refs");
            }
            index++;
        }
        String formKey = text(map(model.get("task_form")), "form_key");
        if (!allowTodo && containsTodo(formKey)) {
            errors.add("task_form.form_key is unresolved");
        }
        if (!allowTodo && !list(model.get("open_questions")).isEmpty()) {
            errors.add("model.open_questions must be resolved before publishing");
        }
        Object confidence = model.get("confidence");
        if (confidence instanceof Number && ((Number) confidence).doubleValue() < 0.5) {
            warnings.add("model confidence is low");
        }
        return report(errors, warnings);
    }

    private Map<String, Object> validateDraft(String draft, boolean allowTodo) {
        List<String> errors = new ArrayList<String>();
        List<String> warnings = new ArrayList<String>();
        String text = draft == null ? "" : draft;
        for (String section : REQUIRED_DRAFT_SECTIONS) {
            if (!text.contains(section)) {
                errors.add("missing required section: " + section);
            }
        }
        if (!allowTodo && containsTodo(text)) {
            errors.add("draft contains unresolved TODO markers");
        }
        if (!text.contains("Источник") && !text.contains("Трассировка источников")) {
            errors.add("draft has no source tracing");
        }
        if (text.trim().length() < 200) {
            warnings.add("draft is unexpectedly short");
        }
        return report(errors, warnings);
    }

    private static Map<String, Object> report(List<String> errors, List<String> warnings) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", errors.isEmpty());
        result.put("errors", errors);
        result.put("warnings", warnings);
        return result;
    }

    private static boolean containsTodo(String value) {
        return Pattern.compile("TODO|требует подтверждения|\\?\\?\\?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(value == null ? "" : value)
                .find();
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<String>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : java.util.Collections.emptyMap();
    }

    private static List<?> list(Object value) {
        return value instanceof List ? (List<?>) value : java.util.Collections.emptyList();
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
