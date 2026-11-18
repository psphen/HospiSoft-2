package com.hospisoft.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.service.PageAccess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AuditoriaPageTest {

    private static final Path WEB = Paths.get("src", "main", "resources", "web");
    private static final Pattern OPTION = Pattern.compile("<option value=\"([A-Z_]*)\">");

    private static String read(String file) throws IOException {
        return Files.readString(WEB.resolve(file), StandardCharsets.UTF_8);
    }

    private static List<String> optionValues(String html, String selectId) {
        Matcher select = Pattern.compile("<select[^>]*id=\"" + selectId + "\"[^>]*>(.*?)</select>", Pattern.DOTALL)
            .matcher(html);
        assertTrue(select.find(), "select not found: " + selectId);
        List<String> values = new ArrayList<>();
        Matcher option = OPTION.matcher(select.group(1));
        while (option.find()) {
            if (!option.group(1).isEmpty()) {
                values.add(option.group(1));
            }
        }
        return values;
    }

    @Test
    void filterSelectsListEveryEnumValue() throws IOException {
        String html = read("auditoria.html");
        assertEquals(Arrays.stream(AccionAuditoria.values()).map(Enum::name).toList(), optionValues(html, "filter-accion"));
        assertEquals(Arrays.stream(ModuloAuditoria.values()).map(Enum::name).toList(), optionValues(html, "filter-modulo"));
    }

    @Test
    void thePageIsReadOnlyAndUsesTheListMethod() throws IOException {
        String html = read("auditoria.html");
        assertFalse(html.contains("id=\"btn-add\""), "the audit log has no add button");
        assertFalse(html.contains("modal"), "the audit log has no edit or delete dialogs");
        assertTrue(html.contains("id=\"header-actions\""), "room for the future export button");
        for (String id : List.of("filter-search", "filter-desde", "filter-hasta", "btn-filter", "auditoria-body",
            "pagination", "page-summary")) {
            assertTrue(html.contains("id=\"" + id + "\""), "missing #" + id);
        }
        assertTrue(html.contains("<script src=\"js/auditoria.js\"></script>"));
        assertTrue(html.contains("href=\"auditoria.html\" class=\"nav-link active\""));
        String js = read("js/auditoria.js");
        assertTrue(js.contains("appCall('listAuditoria'"));
        assertFalse(js.contains("save") || js.contains("delete"), "the audit page never writes");
    }

    @Test
    void everyPageWithASidebarLinksTheAuditLog() throws IOException {
        int pages = 0;
        for (String page : PageAccess.PAGES) {
            String html = read(page);
            if (!html.contains("class=\"sidebar")) {
                continue;
            }
            pages++;
            assertTrue(html.contains("<a href=\"auditoria.html\" class=\"nav-link"), page + " has no Auditoría link");
            assertTrue(html.contains("fa-clipboard-list"), page);
        }
        assertEquals(PageAccess.PAGES.size() - 1, pages, "every page but login has a sidebar");
    }
}
