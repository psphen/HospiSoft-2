package com.hospisoft.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ExportPageTest {

    private static final Path WEB = Paths.get("src", "main", "resources", "web");
    private static final Map<String, String[]> PAGES = Map.of(
        "pacientes.html", new String[] {"js/pacientes.js", "exportPacientes"},
        "medicos.html", new String[] {"js/medicos.js", "exportMedicos"},
        "citas.html", new String[] {"js/citas.js", "exportCitas"},
        "medicamentos.html", new String[] {"js/medicamentos.js", "exportMedicamentos"},
        "auditoria.html", new String[] {"js/auditoria.js", "exportAuditoria"});

    private static String read(String file) throws IOException {
        return Files.readString(WEB.resolve(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String menu(String html, String page) {
        Matcher m = Pattern.compile("<div class=\"dropdown d-none\" id=\"export-menu\">(.*?)</ul>\\s*</div>",
            Pattern.DOTALL).matcher(html);
        assertTrue(m.find(), "no hidden #export-menu dropdown in " + page);
        return m.group(1);
    }

    @Test
    void everyListPageHasTheExportDropdown() throws IOException {
        for (String page : PAGES.keySet()) {
            String menu = menu(read(page), page);
            assertTrue(menu.contains("id=\"btn-export\""), page);
            assertTrue(menu.contains("data-bs-toggle=\"dropdown\""), page);
            assertTrue(menu.contains("fa-file-export"), page);
            assertTrue(menu.contains("Exportar"), page);
            assertTrue(menu.contains("data-export-format=\"xlsx\"") && menu.contains("fa-file-excel")
                && menu.contains("Excel (.xlsx)"), page);
            assertTrue(menu.contains("data-export-format=\"pdf\"") && menu.contains("fa-file-pdf"), page);
        }
    }

    @Test
    void theDropdownSitsInTheHeaderRow() throws IOException {
        for (String page : PAGES.keySet()) {
            String html = read(page);
            int menu = html.indexOf("id=\"export-menu\"");
            int anchor = page.equals("auditoria.html")
                ? html.indexOf("id=\"header-actions\"")
                : html.indexOf("id=\"btn-add\"");
            assertTrue(anchor > 0, page);
            assertTrue(Math.abs(anchor - menu) < 1200, "export menu is not next to the header action in " + page);
            if (page.equals("auditoria.html")) {
                assertTrue(anchor < menu, "the audit export goes inside #header-actions");
            }
        }
    }

    @Test
    void everyPageScriptBindsItsExportMethod() throws IOException {
        for (Map.Entry<String, String[]> entry : PAGES.entrySet()) {
            String js = read(entry.getValue()[0]);
            assertTrue(js.contains("crud.bindExport("), entry.getKey());
            assertTrue(js.contains("'" + entry.getValue()[1] + "'"), entry.getKey() + " must call " + entry.getValue()[1]);
            assertTrue(js.contains("currentFilter"), entry.getKey() + " exports the current filter");
            assertTrue(read(entry.getKey()).contains("<script src=\"js/crud.js\"></script>"), entry.getKey());
        }
    }

    @Test
    void exportsUseALongPerCallTimeout() throws IOException {
        String app = read("js/app.js");
        assertTrue(app.contains("function appCall(method, payload, options)"), "appCall takes an options argument");
        assertTrue(app.contains("options.timeoutMs"), "per-call timeout override");
        assertTrue(app.contains("var ASYNC_TIMEOUT_MS = 40000;"), "the default timeout is unchanged");

        String crud = read("js/crud.js");
        Matcher timeout = Pattern.compile("var EXPORT_TIMEOUT_MS = (\\d+) \\* 60 \\* 1000;").matcher(crud);
        assertTrue(timeout.find(), "crud.js declares the export timeout in minutes");
        assertTrue(Integer.parseInt(timeout.group(1)) >= 10, "the save dialog may stay open for minutes");
        assertTrue(crud.contains("{ timeoutMs: EXPORT_TIMEOUT_MS }"));
        assertTrue(crud.contains("bindExport: bindExport"));
        assertEquals(1, countOf(crud, "appCall("), "one bridge call site in crud.js");
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
