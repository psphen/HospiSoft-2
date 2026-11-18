package com.hospisoft.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.bridge.Bridge;
import com.hospisoft.bridge.Json;
import com.hospisoft.dao.InMemoryMedicamentoRepository;
import com.hospisoft.dao.UsuarioRepository;
import com.hospisoft.export.FakeFileSaver;
import com.hospisoft.model.Medicamento;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.model.Rol;
import com.hospisoft.service.AuditLog;
import com.hospisoft.service.AuthService;
import com.hospisoft.service.ConflictException;
import com.hospisoft.service.ExportService;
import com.hospisoft.service.MedicamentoService;
import com.hospisoft.service.RecordingAuditLog;
import com.hospisoft.service.SessionContext;
import com.hospisoft.service.SessionHolder;
import com.hospisoft.service.StartupStatus;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end medicamento methods through the async bridge with the in-memory repository. */
class MedicamentoControllerTest {

    private static final SessionContext ADMIN = new SessionContext(1, "admin", Rol.ADMIN, null, "Administrador");
    private static final SessionContext PACIENTE = new SessionContext(3, "paciente1", Rol.PACIENTE, 20L, "Maria");
    private static final UsuarioRepository NO_USERS = username -> Optional.empty();

    private final SessionHolder sessions = new SessionHolder();
    private final BlockingQueue<String> scripts = new ArrayBlockingQueue<>(16);
    private InMemoryMedicamentoRepository repository;
    private FakeFileSaver saver;
    private RecordingAuditLog exportAudit;
    private ExportService exports;
    private Bridge bridge;
    @TempDir
    Path dir;
    private int sequence;

    @BeforeEach
    void setUp() {
        repository = new InMemoryMedicamentoRepository();
        repository.add(new Medicamento(1L, "MED-001", "Acetaminofen", "Analgesico", "Caja x 20", 150, 30, true));
        repository.add(new Medicamento(2L, "MED-002", "Ibuprofeno", null, "Caja x 10", 25, 30, true));
        repository.add(new Medicamento(3L, "MED-005", "Loratadina", null, "Caja x 10", 0, 15, false));
        bridge = new Bridge(new AuthService(NO_USERS), sessions, StartupStatus::ok, page -> { }, scripts::add);
        saver = new FakeFileSaver(null);
        exportAudit = new RecordingAuditLog();
        exports = ControllerTestSupport.exports(saver, exportAudit, sessions);
        new MedicamentoController(new MedicamentoService(repository, sessions::current, AuditLog.NONE), exports)
            .register(bridge);
        sessions.set(ADMIN);
    }

    @Test
    void registersExactlyTheMedicamentoMethods() {
        MedicamentoController controller = new MedicamentoController(
            new MedicamentoService(repository, sessions::current, AuditLog.NONE), exports);
        assertEquals(Set.of("listMedicamentos", "getMedicamento", "saveMedicamento", "deleteMedicamento",
                "exportMedicamentos"),
            ControllerTestSupport.registeredNames(controller));
    }

    @AfterEach
    void tearDown() {
        bridge.shutdown();
    }

    private Map<String, Object> invoke(String method, String payload) throws InterruptedException {
        String requestId = "m" + (++sequence);
        assertEquals(true, Json.parseObject(bridge.call(requestId, method, payload)).get("ok"));
        String script = scripts.poll(10, TimeUnit.SECONDS);
        assertTrue(script != null, "no response for " + method);
        String prefix = "if (window.__hospisoft) { window.__hospisoft.resolve(";
        String args = script.substring(prefix.length(), script.length() - "); }".length());
        @SuppressWarnings("unchecked")
        List<Object> parsed = (List<Object>) Json.parse("[" + args + "]");
        assertEquals(requestId, parsed.get(0));
        return Json.parseObject((String) parsed.get(1));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> envelope) {
        assertEquals(true, envelope.get("ok"), "expected success: " + envelope);
        return (Map<String, Object>) envelope.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    @Test
    void listWithStockStateFilterAndDtoShape() throws InterruptedException {
        Map<String, Object> all = data(invoke("listMedicamentos", "{}"));
        assertEquals(3L, all.get("total"));
        Map<String, Object> first = items(all).get(0);
        assertEquals("MED-001", first.get("codigo"));
        assertEquals(150L, first.get("stock"));
        assertEquals(30L, first.get("stockMinimo"));
        assertEquals("DISPONIBLE", first.get("stockState"));
        assertEquals(true, first.get("activo"));

        Map<String, Object> low = data(invoke("listMedicamentos", "{\"filter\":{\"stockState\":\"stock_bajo\"}}"));
        assertEquals(1L, low.get("total"));
        assertEquals("Ibuprofeno", items(low).get(0).get("nombre"));
        Map<String, Object> out = data(invoke("listMedicamentos", "{\"filter\":{\"stockState\":\"AGOTADO\",\"texto\":\"lora\"}}"));
        assertEquals(1L, out.get("total"));
        assertEquals("BAD_REQUEST", invoke("listMedicamentos", "{\"filter\":{\"stockState\":\"nope\"}}").get("code"));
    }

    @Test
    void activoFilterMapping() throws InterruptedException {
        assertEquals(2L, data(invoke("listMedicamentos", "{\"filter\":{\"activo\":\"true\"}}")).get("total"));
        assertEquals(1L, data(invoke("listMedicamentos", "{\"filter\":{\"activo\":\"false\"}}")).get("total"));
        assertEquals(2L, data(invoke("listMedicamentos", "{\"filter\":{\"activo\":true}}")).get("total"));
        assertEquals(3L, data(invoke("listMedicamentos", "{\"filter\":{}}")).get("total"));
        assertEquals(3L, data(invoke("listMedicamentos", "{\"filter\":{\"activo\":null}}")).get("total"));
        assertEquals("BAD_REQUEST", invoke("listMedicamentos", "{\"filter\":{\"activo\":\"si\"}}").get("code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveRoundTripValidationAndConflict() throws InterruptedException {
        Map<String, Object> saved = data(invoke("saveMedicamento",
            "{\"codigo\":\"MED-020\",\"nombre\":\"Omeprazol\",\"presentacion\":\"Caja\",\"stock\":20,\"stockMinimo\":20}"));
        long id = (Long) saved.get("id");
        assertEquals("STOCK_BAJO", saved.get("stockState"));
        assertEquals(true, saved.get("activo"));
        assertEquals("Omeprazol", data(invoke("getMedicamento", "{\"id\":" + id + "}")).get("nombre"));

        Map<String, Object> invalid = invoke("saveMedicamento",
            "{\"codigo\":\"MED-001\",\"nombre\":\"\",\"presentacion\":\"P\",\"stock\":2.5,\"stockMinimo\":-3}");
        assertEquals("VALIDATION", invalid.get("code"));
        Map<String, Object> errors = (Map<String, Object>) invalid.get("errors");
        assertEquals("Ya existe un registro con este código", errors.get("codigo"));
        assertEquals("Campo obligatorio", errors.get("nombre"));
        assertEquals("Debe ser un número entero", errors.get("stock"));
        assertEquals("Debe ser mayor o igual a 0", errors.get("stockMinimo"));

        Map<String, Object> blank = invoke("saveMedicamento",
            "{\"codigo\":\"MED-021\",\"nombre\":\"N\",\"presentacion\":\"P\",\"stock\":\"  \",\"stockMinimo\":1}");
        assertEquals("Campo obligatorio", ((Map<String, Object>) blank.get("errors")).get("stock"));

        // update without stockExpected is rejected
        Map<String, Object> stale = invoke("saveMedicamento",
            "{\"id\":" + id + ",\"codigo\":\"MED-020\",\"nombre\":\"Omeprazol 20\",\"presentacion\":\"Caja\",\"stock\":0,\"stockMinimo\":5}");
        assertEquals("VALIDATION", stale.get("code"));
        assertEquals("Recargue el registro", ((Map<String, Object>) stale.get("errors")).get("stock"));

        // with the loaded stock it goes through
        Map<String, Object> kept = data(invoke("saveMedicamento",
            "{\"id\":" + id + ",\"codigo\":\"MED-020\",\"nombre\":\"Omeprazol 20\",\"presentacion\":\"Caja\",\"stock\":0,\"stockMinimo\":5,\"stockExpected\":20}"));
        assertEquals("AGOTADO", kept.get("stockState"));

        // a stale form (stockExpected 20, stock is now 0) answers CONFLICT
        Map<String, Object> conflict = invoke("saveMedicamento",
            "{\"id\":" + id + ",\"codigo\":\"MED-020\",\"nombre\":\"Omeprazol 20\",\"presentacion\":\"Caja\",\"stock\":7,\"stockMinimo\":5,\"stockExpected\":20}");
        assertEquals(false, conflict.get("ok"));
        assertEquals("CONFLICT", conflict.get("code"));
        assertEquals(ConflictException.MESSAGE, conflict.get("message"));
        assertEquals(0, repository.findById(id).orElseThrow().stock());

        assertEquals("NOT_FOUND", invoke("getMedicamento", "{\"id\":999}").get("code"));
    }

    @Test
    void deleteOutcomesAndPermissions() throws InterruptedException {
        assertEquals("DELETED", data(invoke("deleteMedicamento", "{\"id\":3}")).get("outcome"));
        repository.markHistory(1);
        assertEquals("DEACTIVATED", data(invoke("deleteMedicamento", "{\"id\":1}")).get("outcome"));

        sessions.set(PACIENTE);
        assertEquals(2L, data(invoke("listMedicamentos", "{}")).get("total"));
        assertEquals("Ibuprofeno", data(invoke("getMedicamento", "{\"id\":2}")).get("nombre"));
        assertEquals("FORBIDDEN", invoke("saveMedicamento", "{\"codigo\":\"X\"}").get("code"));
        assertEquals("FORBIDDEN", invoke("deleteMedicamento", "{\"id\":2}").get("code"));
        sessions.clear();
        assertEquals("FORBIDDEN", invoke("listMedicamentos", "{}").get("code"));
    }

    // ------------------------------------------------------------ export

    @Test
    void exportWritesStockLabelsAndActivo() throws Exception {
        saver.answer(dir.resolve("inventario.xlsx"));
        assertEquals(3L, data(invoke("exportMedicamentos", "{\"format\":\"xlsx\"}")).get("rows"));
        assertEquals("medicamentos_2026-11-04.xlsx", saver.last().suggestedFileName());
        List<List<String>> table = ControllerTestSupport.table(dir.resolve("inventario.xlsx"));
        assertEquals(List.of("#", "Código", "Nombre", "Presentación", "Stock", "Stock mín.", "Estado", "Activo"),
            table.get(0));
        assertTrue(table.contains(List.of("1", "MED-001", "Acetaminofen", "Caja x 20", "150", "30", "Disponible", "Sí")));
        assertTrue(table.contains(List.of("2", "MED-002", "Ibuprofeno", "Caja x 10", "25", "30", "Stock bajo", "Sí")));
        assertTrue(table.contains(List.of("3", "MED-005", "Loratadina", "Caja x 10", "0", "15", "Agotado", "No")));
        assertEquals(ModuloAuditoria.MEDICAMENTO, exportAudit.only().modulo());

        saver.answer(dir.resolve("agotados.pdf"));
        assertEquals(1L, data(invoke("exportMedicamentos", "{\"filter\":{\"stockState\":\"agotado\"},\"format\":\"pdf\"}"))
            .get("rows"));
        assertEquals("BAD_REQUEST", invoke("exportMedicamentos", "{\"filter\":{\"stockState\":\"X\"},\"format\":\"pdf\"}")
            .get("code"));
    }
}
