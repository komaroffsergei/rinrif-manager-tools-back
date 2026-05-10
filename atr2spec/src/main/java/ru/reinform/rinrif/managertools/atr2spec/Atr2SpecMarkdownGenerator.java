package ru.reinform.rinrif.managertools.atr2spec;

import java.util.List;
import java.util.Map;

class Atr2SpecMarkdownGenerator {
    String render(Map<String, Object> model) {
        Map<?, ?> source = firstMap(model.get("source_refs"));
        Map<?, ?> taskForm = map(model.get("task_form"));
        Map<?, ?> process = map(model.get("process"));
        Map<?, ?> target = map(model.get("target_artifact"));
        String title = firstFilled(text(taskForm, "name"), text(target, "title"), "Черновик постановки");

        StringBuilder lines = new StringBuilder();
        append(lines, "# " + title, "");
        append(lines, "## Версионность", "");
        append(lines, "| № версии | Описание изменений | Автор | Дата | Задача |");
        append(lines, "|---|---|---|---|---|");
        append(lines, "| 1 | TODO: черновик сформирован из АТР, требуется проверка аналитиком | ReInform | TODO | TODO |", "");
        append(lines, "## Связанная задача", "");
        append(lines, "- Источник: " + firstFilled(text(source, "url"), text(source, "page_id"), "TODO: источник требует подтверждения"));
        append(lines, "- MGSN: " + firstFilled(joinList(target.get("mgsn_keys")), "TODO: MGSN требует подтверждения"));
        append(lines, "- Jira: " + firstFilled(joinList(target.get("jira_keys")), "TODO: Jira задача требует подтверждения"), "");
        append(lines, "## Спецификация", "");
        append(lines, "- Процесс: " + firstFilled(text(process, "code"), "TODO: код процесса требует подтверждения"));
        append(lines, "- Наименование процесса: " + firstFilled(text(process, "name"), "TODO: наименование процесса требует подтверждения"));
        append(lines, "- Переменные процесса: " + firstFilled(text(process, "variables"), "TODO: переменные процесса требуют подтверждения"));
        append(lines, "- Наименование задачи: " + firstFilled(text(taskForm, "name"), "TODO: наименование задачи требует подтверждения"));
        append(lines, "- Ключ формы: " + firstFilled(text(taskForm, "form_key"), "TODO: ключ формы требует подтверждения"));
        append(lines, "- Ссылка на форму: " + firstFilled(text(taskForm, "url"), "TODO: ссылка на форму требует подтверждения"), "");
        append(lines, "## Постановка", "");
        append(lines, "### Атрибуты формы", "");
        append(lines, "| № | Наименование | Тип | Описание / Ограничения / Действие по нажатию | Чем заполнять | Куда сохранять | Источник |");
        append(lines, "|---|---|---|---|---|---|---|");

        List<?> fields = list(model.get("fields"));
        if (fields.isEmpty()) {
            append(lines, "| 1 | TODO | TODO | Атрибуты формы не извлечены автоматически | TODO | TODO | TODO |");
        } else {
            int index = 1;
            for (Object fieldValue : fields) {
                Map<?, ?> field = map(fieldValue);
                String fillFrom = firstFilled(text(field, "fill_from"), text(field, "initial_value"), "TODO: источник заполнения требует подтверждения");
                append(lines, "| "
                        + index++ + " | "
                        + escapeCell(firstFilled(text(field, "name"), "TODO")) + " | "
                        + escapeCell(firstFilled(text(field, "type"), "TODO")) + " | "
                        + escapeCell(firstFilled(text(field, "description"), "TODO: описание требует подтверждения")) + " | "
                        + escapeCell(fillFrom) + " | "
                        + escapeCell(firstFilled(text(field, "save_to"), "TODO: место сохранения требует подтверждения")) + " | "
                        + escapeCell(renderSourceRefs(field.get("source_refs"))) + " |");
            }
        }

        append(lines, "", "### Кнопки", "");
        List<?> actions = list(model.get("actions"));
        if (actions.isEmpty()) {
            append(lines, "- TODO: действия кнопок требуют подтверждения");
        } else {
            for (Object value : actions) {
                Map<?, ?> action = map(value);
                append(lines, "- " + firstFilled(text(action, "description"), text(action, "name"))
                        + " _(источник: " + renderSourceRefs(action.get("source_refs")) + ")_");
            }
        }

        append(lines, "", "### Проверки и ограничения", "");
        renderItems(lines, list(model.get("validations")), "- TODO: проверки и ограничения требуют подтверждения");

        append(lines, "", "### Сервисы и справочники", "");
        renderLabeledItems(lines, "Сервисы", list(model.get("services")));
        renderLabeledItems(lines, "Справочники", list(model.get("dictionaries")));

        append(lines, "", "### Открытые вопросы", "");
        List<?> questions = list(model.get("open_questions"));
        if (questions.isEmpty()) {
            append(lines, "- Нет автоматически выявленных вопросов.");
        } else {
            renderItems(lines, questions, "");
        }

        append(lines, "", "## Трассировка источников", "");
        append(lines, "- Confluence page: " + text(source, "page_id"));
        append(lines, "- Версия источника: " + text(source, "version"));
        append(lines, "- Уверенность извлечения: " + String.valueOf(model.get("confidence")));
        return lines.toString().trim() + "\n";
    }

    private void renderLabeledItems(StringBuilder lines, String label, List<?> items) {
        if (items.isEmpty()) {
            return;
        }
        append(lines, "**" + label + ":**");
        renderItems(lines, items, "");
    }

    private void renderItems(StringBuilder lines, List<?> items, String emptyLine) {
        if (items.isEmpty()) {
            if (!emptyLine.isEmpty()) {
                append(lines, emptyLine);
            }
            return;
        }
        for (Object value : items) {
            Map<?, ?> item = map(value);
            append(lines, "- " + text(item, "text") + " _(источник: " + renderSourceRefs(item.get("source_refs")) + ")_");
        }
    }

    private static void append(StringBuilder target, String... values) {
        for (String value : values) {
            target.append(value == null ? "" : value).append('\n');
        }
    }

    private static String renderSourceRefs(Object refs) {
        String result = joinList(refs);
        return result.isEmpty() ? "TODO: источник требует подтверждения" : result;
    }

    private static String escapeCell(String value) {
        return (value == null ? "" : value).replace("|", "\\|").replace("\n", "<br>");
    }

    private static Map<?, ?> firstMap(Object value) {
        List<?> list = list(value);
        return list.isEmpty() ? java.util.Collections.emptyMap() : map(list.get(0));
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

    private static String joinList(Object value) {
        if (!(value instanceof List)) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (Object item : (List<?>) value) {
            if (item == null) {
                continue;
            }
            if (result.length() > 0) {
                result.append(", ");
            }
            result.append(item);
        }
        return result.toString();
    }

    private static String firstFilled(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }
}
