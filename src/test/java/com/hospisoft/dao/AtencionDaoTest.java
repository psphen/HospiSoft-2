package com.hospisoft.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.db.ConnectionPool;
import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.HistoriaView;
import com.hospisoft.model.Page;
import com.hospisoft.model.RecetaView;
import com.hospisoft.service.ConflictException;
import com.hospisoft.service.ValidationException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AtencionDaoTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 10, 27, 9, 0, 0, 0, ZoneOffset.ofHours(-5));

    private final FakeJdbc jdbc = new FakeJdbc();
    private final AtencionDao dao = new AtencionDao(new ConnectionPool(jdbc::connection, 1, 100, System::currentTimeMillis));

    private static Map<String, Object> historiaRow(long id, long idCita) {
        return FakeJdbc.row("id", id, "id_cita", idCita, "id_paciente", 20L, "id_medico", 10L, "fecha", T0,
            "diagnostico", "Gripe", "tratamiento", "Reposo", "notas", null,
            "medico_nombres", "Carlos", "medico_apellidos", "Garcia", "especialidad", "Medicina General",
            "paciente_nombres", "Maria", "paciente_apellidos", "Torres", "paciente_documento", "2001");
    }

    private static Map<String, Object> recetaRow(long id, boolean entregado) {
        return FakeJdbc.row("id", id, "id_historia", 7L, "id_medicamento", 5L, "cantidad", 9, "dosis", "1 c/8h",
            "duracion", "3 dias", "entregado", entregado, "observacion_no_disponible", entregado ? null : "Sin stock",
            "codigo", "MED-001", "nombre", "Acetaminofen", "presentacion", "Caja x 20");
    }

    // ------------------------------------------------------------ reads

    @Test
    void historiaOfACitaCarriesItsRecetas() {
        jdbc.rows(historiaRow(7, 3)).rows(recetaRow(1, true), recetaRow(2, false));
        HistoriaView view = dao.findHistoriaByCita(3).orElseThrow();
        assertEquals(AtencionDao.SELECT_HISTORIA + " WHERE h.id_cita = ?", jdbc.call(0).sql);
        assertEquals(List.of(3L), jdbc.call(0).values());
        assertEquals(AtencionDao.SELECT_RECETAS_BY_HISTORIA, jdbc.call(1).sql);
        assertEquals(List.of(7L), jdbc.call(1).values());
        assertEquals("Carlos Garcia", view.medicoNombre());
        assertEquals("Maria Torres", view.pacienteNombre());
        assertEquals(2, view.recetas().size());
        assertTrue(view.recetas().get(0).receta().entregado());
        assertEquals("Sin stock", view.recetas().get(1).receta().observacionNoDisponible());
        assertEquals("Acetaminofen", view.recetas().get(0).nombre());

        jdbc.rows();
        assertTrue(dao.findHistoriaByCita(99).isEmpty());
    }

    @Test
    void historiaByIdAndRecetasByHistoria() {
        jdbc.rows(historiaRow(7, 3)).rows();
        assertTrue(dao.findHistoriaById(7).isPresent());
        assertEquals(AtencionDao.SELECT_HISTORIA + " WHERE h.id = ?", jdbc.call(0).sql);
        jdbc.rows(recetaRow(1, true));
        List<RecetaView> lines = dao.findRecetasByHistoria(7);
        assertEquals(1, lines.size());
        assertEquals("MED-001", lines.get(0).codigo());
    }

    @Test
    void historiasPageFiltersAndOrdersNewestFirst() {
        jdbc.rows(FakeJdbc.row("count", 2L)).rows(historiaRow(7, 3));
        Page<HistoriaView> page = dao.findHistoriasPage(new HistoriaQuery(20L, 10L, "gri"), 2, 5);
        String where = " WHERE h.id_paciente = ? AND h.id_medico = ? AND " + AtencionDao.HISTORIA_TEXT_CLAUSE;
        assertEquals(AtencionDao.COUNT_HISTORIA + where, jdbc.call(0).sql);
        assertEquals(List.of(20L, 10L, "%gri%", "%gri%", "%gri%"), jdbc.call(0).values());
        assertEquals(AtencionDao.SELECT_HISTORIA + where + AtencionDao.HISTORIA_ORDER + AtencionDao.PAGING,
            jdbc.call(1).sql);
        assertEquals(List.of(20L, 10L, "%gri%", "%gri%", "%gri%", 5L, 5L), jdbc.call(1).values());
        assertTrue(AtencionDao.HISTORIA_ORDER.contains("DESC"));
        assertEquals(2, page.total());
        assertEquals(List.of(), page.items().get(0).recetas(), "list rows carry no recetas");

        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findHistoriasPage(null, 1, 10);
        assertEquals(AtencionDao.COUNT_HISTORIA, jdbc.call(2).sql);
        assertEquals(List.of(), jdbc.call(2).values());
    }

    @Test
    void recetasPageOfAPatientCarriesTheHistoriaDate() {
        Map<String, Object> row = FakeJdbc.row("id", 1L, "id_historia", 7L, "id_medicamento", 5L, "cantidad", 9,
            "dosis", "1 c/8h", "duracion", "3 dias", "entregado", true, "observacion_no_disponible", null,
            "codigo", "MED-001", "nombre", "Acetaminofen", "presentacion", "Caja x 20", "fecha", T0);
        jdbc.rows(FakeJdbc.row("count", 1L)).rows(row);
        Page<RecetaView> page = dao.findRecetasPage(20, 1, 10);
        assertEquals(AtencionDao.COUNT_RECETAS_PAGE, jdbc.call(0).sql);
        assertEquals(List.of(20L), jdbc.call(0).values());
        assertEquals(AtencionDao.SELECT_RECETAS_PAGE, jdbc.call(1).sql);
        assertEquals(List.of(20L, 10L, 0L), jdbc.call(1).values());
        assertEquals(T0, page.items().get(0).receta().fechaHistoria());
    }

    // ------------------------------------------------------------ transaction

    @Test
    void iniciarLocksTheMedicoChecksEnCursoAndGuardsTheState() {
        jdbc.rows(FakeJdbc.row("activo", true))
            .rows(FakeJdbc.row("exists", false))
            .update(1);
        int affected = dao.inTransaction(tx -> {
            assertEquals(Optional.of(true), tx.lockMedico(10));
            assertFalse(tx.existsEnCurso(10, 3L));
            return tx.iniciar(3, 10);
        });
        assertEquals(1, affected);
        assertEquals(List.of("setAutoCommit(true)", "setAutoCommit(false)", "commit", "setAutoCommit(true)"), jdbc.events);
        assertEquals("SELECT activo FROM medico WHERE id = ? FOR NO KEY UPDATE", jdbc.call(0).sql);
        assertEquals(AtencionDao.EXISTS_EN_CURSO, jdbc.call(1).sql);
        assertEquals(List.of(10L, 3L), jdbc.call(1).values());
        assertEquals(AtencionDao.INICIAR, jdbc.call(2).sql);
        assertEquals(List.of(3L, 10L), jdbc.call(2).values());
        assertTrue(AtencionDao.INICIAR.contains("estado = 'PROGRAMADA'"));
        assertTrue(AtencionDao.INICIAR.contains("hora_inicio_real = now()"));
    }

    @Test
    void estadoOfOwnCitaDistinguishesMissingFromWrongState() {
        jdbc.rows(FakeJdbc.row("estado", "EN_CURSO"));
        assertEquals(Optional.of(EstadoCita.EN_CURSO), dao.inTransaction(tx -> tx.estadoOfOwnCita(3, 10)));
        assertEquals(AtencionDao.ESTADO_OF_OWN_CITA, jdbc.call(0).sql);
        assertEquals(List.of(3L, 10L), jdbc.call(0).values());
        jdbc.rows();
        assertTrue(dao.inTransaction(tx -> tx.estadoOfOwnCita(3, 10)).isEmpty());
    }

    @Test
    void lockMedicamentoReadsStockAndFlags() {
        jdbc.rows(FakeJdbc.row("stock", 150, "stock_minimo", 30, "activo", true));
        AtencionRepository.MedicamentoLock lock = dao.inTransaction(tx -> tx.lockMedicamento(5)).orElseThrow();
        assertEquals("SELECT stock, stock_minimo, activo FROM medicamento WHERE id = ? FOR NO KEY UPDATE",
            jdbc.call(0).sql);
        assertEquals(List.of(5L), jdbc.call(0).values());
        assertEquals(150, lock.stock());
        assertEquals(30, lock.stockMinimo());
        assertTrue(lock.activo());
        jdbc.rows();
        assertTrue(dao.inTransaction(tx -> tx.lockMedicamento(5)).isEmpty());
    }

    @Test
    void finalizarInsertsAndDecrementsWithTheExpectedSql() {
        jdbc.update(1).rows(FakeJdbc.row("id", 7L)).rows(FakeJdbc.row("id", 1L)).update(1);
        long historia = dao.inTransaction(tx -> {
            assertEquals(1, tx.finalizar(3, 10, "Todo bien"));
            long id = tx.insertHistoria(3, 20, 10, "Gripe", "Reposo", null);
            tx.insertReceta(id, 5, 9, "1 c/8h", "3 dias", true, null);
            assertEquals(1, tx.decrementStock(5, 9));
            return id;
        });
        assertEquals(7, historia);
        assertEquals(AtencionDao.FINALIZAR, jdbc.call(0).sql);
        assertEquals(List.of("Todo bien", 3L, 10L), jdbc.call(0).values());
        assertTrue(AtencionDao.FINALIZAR.contains("estado = 'EN_CURSO'"));
        assertTrue(AtencionDao.FINALIZAR.contains("hora_fin_real = now()"));
        assertEquals(AtencionDao.INSERT_HISTORIA, jdbc.call(1).sql);
        assertEquals(6, jdbc.call(1).params.size());
        assertEquals(java.util.Set.of(6), jdbc.call(1).nulls);
        assertEquals(AtencionDao.INSERT_RECETA, jdbc.call(2).sql);
        assertEquals(java.util.Arrays.asList(7L, 5L, 9, "1 c/8h", "3 dias", true, null), jdbc.call(2).values());
        assertEquals(AtencionDao.DECREMENT_STOCK, jdbc.call(3).sql);
        assertEquals(List.of(9, 5L, 9), jdbc.call(3).values());
        assertTrue(AtencionDao.DECREMENT_STOCK.contains("stock = stock - ? WHERE id = ? AND stock >= ?"));
    }

    @Test
    void duplicateHistoriaIsAConflictAndRollsBack() {
        jdbc.fail("23505", "duplicate key value violates unique constraint \"historia_clinica_id_cita_key\"");
        ConflictException ex = assertThrows(ConflictException.class,
            () -> dao.inTransaction(tx -> tx.insertHistoria(3, 20, 10, "Gripe", "Reposo", null)));
        assertEquals(AtencionDao.ALREADY_CLOSED, ex.userMessage());
        assertEquals(List.of("setAutoCommit(true)", "setAutoCommit(false)", "rollback", "setAutoCommit(true)"),
            jdbc.events);
    }

    @Test
    void recetaForeignKeyViolationBecomesAFieldError() {
        jdbc.fail("23503", "violates foreign key constraint \"receta_id_medicamento_fkey\"");
        ValidationException ex = assertThrows(ValidationException.class,
            () -> dao.inTransaction(tx -> tx.insertReceta(7, 999, 1, "d", "d", false, "x")));
        assertEquals("No existe", ex.errors().get("idMedicamento"));
    }

    @Test
    void theEnCursoPartialIndexBecomesAFieldError() {
        jdbc.fail("23505", "duplicate key value violates unique constraint \"cita_un_en_curso_por_medico\"");
        ValidationException ex = assertThrows(ValidationException.class,
            () -> dao.inTransaction(tx -> tx.iniciar(3, 10)));
        assertEquals("Ya tiene una atención en curso", ex.errors().get("estado"));
        // any other unique violation on that statement is not swallowed
        jdbc.fail("23505", "duplicate key value violates unique constraint \"some_other_key\"");
        assertThrows(IllegalStateException.class, () -> dao.inTransaction(tx -> tx.iniciar(3, 10)));
    }

    @Test
    void anotherUniqueViolationOnTheHistoriaIsNotReportedAsAlreadyClosed() {
        jdbc.fail("23505", "duplicate key value violates unique constraint \"unrelated_key\"");
        assertThrows(IllegalStateException.class,
            () -> dao.inTransaction(tx -> tx.insertHistoria(3, 20, 10, "Gripe", "Reposo", null)));
    }

    @Test
    void recetaPageKeepsTheLinesOfAHistoriaInInsertionOrder() {
        assertTrue(AtencionDao.SELECT_RECETAS_PAGE.contains("ORDER BY h.fecha DESC, h.id DESC, r.id"),
            AtencionDao.SELECT_RECETAS_PAGE);
        assertTrue(AtencionDao.SELECT_RECETAS_BY_HISTORIA.endsWith("ORDER BY r.id"));
    }

    @Test
    void decrementStockReportsZeroWhenTheGuardFails() {
        jdbc.update(0);
        assertEquals(0, (int) dao.inTransaction(tx -> tx.decrementStock(5, 999)));
    }
}
