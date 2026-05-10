package ru.reinform.rinrif.managertools.atr2spec;

import org.junit.Assert;
import org.junit.Test;
import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecModels.Atr2SpecPairCandidate;
import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecModels.Atr2SpecValidationReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Atr2SpecModuleTest {
    @Test
    public void htmlToTextKeepsTableSeparators() {
        String html = "<table><tr><th>Поле</th><th>Описание</th></tr><tr><td>Дата</td><td>Дата документа</td></tr></table>";

        String text = Atr2SpecTextUtils.htmlToText(html);

        Assert.assertTrue(text.contains("Поле | Описание"));
        Assert.assertTrue(text.contains("Дата | Дата документа"));
    }

    @Test
    public void pairingRanksLinkedSpecWithDifferentMgsn() {
        Map<String, Object> atr = page("302023572", "MGSN-1294 АТР", "АТР", "Доработка с учетом MGSN-1289 DOLSTR-1884");
        Map<String, Object> spec = page("289812685", "MGSN-1289 ФЗ Проведение КНМ", "Постановки", "Версионность изменениями в АТР с учетом 1294");
        Map<String, Object> issue = Atr2SpecIo.map();
        issue.put("task_key", "DOLSTR-1884");
        issue.put("description_text", "");
        List<Map<String, String>> remoteLinks = new ArrayList<Map<String, String>>();
        Map<String, String> remoteLink = new LinkedHashMap<String, String>();
        remoteLink.put("title", "Постановка");
        remoteLink.put("url", "https://wiki.reinform-int.ru/pages/viewpage.action?pageId=289812685");
        remoteLinks.add(remoteLink);
        issue.put("remote_links", remoteLinks);

        List<Atr2SpecPairCandidate> candidates = new Atr2SpecPairing().buildReport(
                atr,
                list(spec),
                list(issue),
                10
        );

        Assert.assertEquals("289812685", candidates.get(0).pageId);
        Assert.assertTrue(candidates.get(0).reasons.contains("jira_remote_link_points_to_candidate:DOLSTR-1884"));
        Assert.assertTrue(candidates.get(0).score > 0);
    }

    @Test
    public void extractorGeneratorAndValidatorHandleDraftTodoMode() {
        Map<String, Object> page = page(
                "302023572",
                "MGSN-1294 АТР",
                "АТР",
                "1.5. Описание форм задач\n"
                        + "Форма задачи \"Требования о предоставлении документов и пояснений\"\n"
                        + "Поле | Описание | Тип | Обязательность | Множественность | Начальное значение\n"
                        + "Номер требования | Номер документа | Строка | Да | Нет | process/docNumber\n"
                        + "Кнопка Сформировать При нажатии выполняется проверка обязательных полей"
        );

        Map<String, Object> model = new Atr2SpecExtractor().extract(page);
        String draft = new Atr2SpecMarkdownGenerator().render(model);
        Atr2SpecValidationReport strictReport = new Atr2SpecValidator().validate(model, draft, false);
        Atr2SpecValidationReport draftReport = new Atr2SpecValidator().validate(model, draft, true);

        Assert.assertEquals(1, ((List<?>) model.get("fields")).size());
        Assert.assertTrue(draft.contains("Требования о предоставлении документов и пояснений"));
        Assert.assertTrue(draft.contains("Атрибуты формы"));
        Assert.assertFalse(strictReport.ok);
        Assert.assertTrue(draftReport.ok);
    }

    private static Map<String, Object> page(String id, String title, String ancestorTitle, String text) {
        Map<String, Object> page = Atr2SpecIo.map();
        page.put("page_id", id);
        page.put("title", title);
        page.put("url", "https://wiki.reinform-int.ru/pages/viewpage.action?pageId=" + id);
        page.put("version", 1);
        page.put("text", text);
        page.put("jira_keys", Atr2SpecTextUtils.extractJiraKeys(title + "\n" + text));
        page.put("mgsn_keys", Atr2SpecTextUtils.extractMgsnKeys(title + "\n" + text));
        Map<String, String> ancestor = new LinkedHashMap<String, String>();
        ancestor.put("id", "1");
        ancestor.put("title", ancestorTitle);
        page.put("ancestors", list(ancestor));
        return page;
    }

    private static <T> List<T> list(T item) {
        List<T> result = new ArrayList<T>();
        result.add(item);
        return result;
    }
}
