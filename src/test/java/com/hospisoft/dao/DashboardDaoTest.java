package com.hospisoft.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.dao.DashboardRepository.DashboardSnapshot;
import com.hospisoft.dao.DashboardRepository.Scope;
import com.hospisoft.db.ConnectionPool;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DashboardDaoTest {

    private static final OffsetDateTime DAY_START = OffsetDateTime.of(2026, 10, 27, 0, 0, 0, 0, ZoneOffset.ofHours(-5));
    private static final OffsetDateTime DAY_END = DAY_START.plusDays(1);
    private static final OffsetDateTime NOW = DAY_START.plusHours(8);

    private final FakeJdbc jdbc = new FakeJdbc();
    private final DashboardDao dao = new DashboardDao(new ConnectionPool(jdbc::connection, 1, 100, System::currentTimeMillis));

    private static Map<String, Object> citaRow() {
        return FakeJdbc.row("id", 1L, "id_medico", 10L, "id_paciente", 20L, "fecha_hora_programada", NOW.plusHours(1),
            "motivo", "Control", "estado", "PROGRAMADA", "hora_inicio_real", null, "hora_fin_real", null,
            "observaciones", null, "medico_nombres", "Carlos", "medico_apellidos", "Garcia",
            "especialidad", "Medicina General", "paciente_nombres", "Maria", "paciente_apellidos", "Torres",
            "paciente_documento", "2001");
    }

    /** Queues the five scoped answers every snapshot needs, in call order. */
    private void queueScopedAnswers() {
        jdbc.rows(FakeJdbc.row("count", 4L))                                   // citas del dia
            .rows(FakeJdbc.row("count", 3L))                                   // citas pendientes
            .rows(FakeJdbc.row("estado", "FINALIZADA", "count", 2L),           // resumen
                FakeJdbc.row("estado", "PROGRAMADA", "count", 1L),
                FakeJdbc.row("estado", "EN_CURSO", "count", 1L),
                FakeJdbc.row("estado", "CANCELADA", "count", 1L))
            .rows(FakeJdbc.row("avg", 24.5))                                   // duracion promedio
            .rows(citaRow());                                                  // proximas
    }

    @Test
    void globalScopeAsksForTheHospitalWideCounts() {
        queueScopedAnswers();
        jdbc.rows(FakeJdbc.row("count", 3L))    // medicos activos
            .rows(FakeJdbc.row("count", 5L))    // pacientes activos
            .rows(FakeJdbc.row("count", 9L));   // medicamentos activos
        DashboardSnapshot snapshot = dao.snapshot(Scope.ALL, DAY_START, DAY_END, NOW, 5);

        assertEquals(DashboardDao.COUNT_CITAS_DEL_DIA, jdbc.call(0).sql);
        assertEquals(List.of(DAY_START, DAY_END), jdbc.call(0).values());
        assertEquals(DashboardDao.COUNT_CITAS_PENDIENTES, jdbc.call(1).sql);
        assertEquals(List.of(NOW), jdbc.call(1).values());
        assertEquals(DashboardDao.RESUMEN_DEL_DIA + DashboardDao.RESUMEN_GROUP_BY, jdbc.call(2).sql);
        assertEquals(List.of(DAY_START, DAY_END), jdbc.call(2).values());
        assertEquals(DashboardDao.DURACION_PROMEDIO, jdbc.call(3).sql);
        assertEquals(DashboardDao.PROXIMAS_CITAS + DashboardDao.PROXIMAS_ORDER, jdbc.call(4).sql);
        assertEquals(List.of(NOW, 5), jdbc.call(4).values());
        assertEquals(DashboardDao.COUNT_MEDICOS_ACTIVOS, jdbc.call(5).sql);
        assertEquals(DashboardDao.COUNT_PACIENTES_ACTIVOS, jdbc.call(6).sql);
        assertEquals(DashboardDao.COUNT_MEDICAMENTOS_ACTIVOS, jdbc.call(7).sql);
        assertEquals(8, jdbc.calls.size(), "no per-scope queries for the global dashboard");

        assertEquals(4, snapshot.citasHoy());
        assertEquals(3, snapshot.citasPendientes());
        assertEquals(5, snapshot.resumenHoy().total());
        assertEquals(2, snapshot.resumenHoy().atendidas());
        assertEquals(1, snapshot.resumenHoy().enCurso());
        assertEquals(1, snapshot.resumenHoy().pendientes());
        assertEquals(1, snapshot.resumenHoy().canceladas());
        assertEquals(4, snapshot.resumenHoy().noCanceladas(), "the cancelled ones do not count as work");
        assertEquals(24.5, snapshot.duracionPromedioMin());
        assertEquals(3, snapshot.medicosActivos());
        assertEquals(5, snapshot.pacientesActivos());
        assertEquals(9, snapshot.medicamentosActivos());
        assertEquals("Carlos Garcia", snapshot.proximasCitas().get(0).medicoNombre());
    }

    @Test
    void medicoScopeAppendsTheMedicoFilterToEveryQuery() {
        queueScopedAnswers();
        jdbc.rows(FakeJdbc.row("count", 7L)); // pacientes atendidos
        DashboardSnapshot snapshot = dao.snapshot(Scope.medico(10), DAY_START, DAY_END, NOW, 5);

        assertEquals(DashboardDao.COUNT_CITAS_DEL_DIA + " AND id_medico = ?", jdbc.call(0).sql);
        assertEquals(List.of(DAY_START, DAY_END, 10L), jdbc.call(0).values());
        assertEquals(DashboardDao.COUNT_CITAS_PENDIENTES + " AND id_medico = ?", jdbc.call(1).sql);
        assertEquals(List.of(NOW, 10L), jdbc.call(1).values());
        assertEquals(DashboardDao.RESUMEN_DEL_DIA + " AND id_medico = ?" + DashboardDao.RESUMEN_GROUP_BY,
            jdbc.call(2).sql);
        assertEquals(List.of(DAY_START, DAY_END, 10L), jdbc.call(2).values());
        assertEquals(DashboardDao.DURACION_PROMEDIO + " AND id_medico = ?", jdbc.call(3).sql);
        assertEquals(DashboardDao.PROXIMAS_CITAS + " AND c.id_medico = ?" + DashboardDao.PROXIMAS_ORDER,
            jdbc.call(4).sql);
        assertEquals(List.of(NOW, 10L, 5), jdbc.call(4).values());
        assertEquals(DashboardDao.COUNT_PACIENTES_DE_MEDICO, jdbc.call(5).sql);
        assertEquals(List.of(10L), jdbc.call(5).values());
        assertTrue(DashboardDao.COUNT_PACIENTES_DE_MEDICO.contains("count(DISTINCT id_paciente)"));
        assertTrue(DashboardDao.COUNT_PACIENTES_DE_MEDICO.contains("estado <> 'CANCELADA'"));
        assertEquals(6, jdbc.calls.size());
        assertEquals(7, snapshot.pacientesAtendidos());
        assertEquals(0, snapshot.medicosActivos(), "global counts are not computed for a scoped dashboard");
    }

    @Test
    void pacienteScopeAddsTheOwnTotals() {
        queueScopedAnswers();
        jdbc.rows(FakeJdbc.row("count", 6L))   // citas totales
            .rows(FakeJdbc.row("count", 4L))   // recetas
            .rows(FakeJdbc.row("count", 2L));  // historias
        DashboardSnapshot snapshot = dao.snapshot(Scope.paciente(20), DAY_START, DAY_END, NOW, 5);

        assertEquals(DashboardDao.COUNT_CITAS_DEL_DIA + " AND id_paciente = ?", jdbc.call(0).sql);
        assertEquals(DashboardDao.PROXIMAS_CITAS + " AND c.id_paciente = ?" + DashboardDao.PROXIMAS_ORDER,
            jdbc.call(4).sql);
        assertEquals(DashboardDao.COUNT_CITAS_DE_PACIENTE, jdbc.call(5).sql);
        assertEquals(DashboardDao.COUNT_RECETAS_DE_PACIENTE, jdbc.call(6).sql);
        assertEquals(DashboardDao.COUNT_HISTORIAS_DE_PACIENTE, jdbc.call(7).sql);
        assertEquals(List.of(20L), jdbc.call(7).values());
        assertEquals(6, snapshot.citasTotales());
        assertEquals(4, snapshot.recetasTotales());
        assertEquals(2, snapshot.historiasTotales());
    }

    @Test
    void emptyDayReportsZerosAndNoAverage() {
        jdbc.rows(FakeJdbc.row("count", 0L))
            .rows(FakeJdbc.row("count", 0L))
            .rows()                                   // no rows grouped by estado
            .rows(FakeJdbc.row("avg", null))          // AVG over no rows is NULL
            .rows()
            .rows(FakeJdbc.row("count", 0L))
            .rows(FakeJdbc.row("count", 0L))
            .rows(FakeJdbc.row("count", 0L));
        DashboardSnapshot snapshot = dao.snapshot(Scope.ALL, DAY_START, DAY_END, NOW, 5);
        assertEquals(0, snapshot.citasHoy());
        assertEquals(0, snapshot.resumenHoy().total());
        assertNull(snapshot.duracionPromedioMin());
        assertEquals(List.of(), snapshot.proximasCitas());
    }

    @Test
    void theAverageIsComputedInSqlOverFinishedCitasOnly() {
        assertTrue(DashboardDao.DURACION_PROMEDIO.contains("AVG(EXTRACT(EPOCH FROM (hora_fin_real - hora_inicio_real)) / 60)"));
        assertTrue(DashboardDao.DURACION_PROMEDIO.contains("estado = 'FINALIZADA'"));
    }

    @Test
    void theUpcomingListOnlyShowsProgramadaSoItAgreesWithThePendingKpi() {
        queueScopedAnswers();
        jdbc.rows(FakeJdbc.row("count", 3L))
            .rows(FakeJdbc.row("count", 5L))
            .rows(FakeJdbc.row("count", 9L));
        dao.snapshot(Scope.ALL, DAY_START, DAY_END, NOW, 5);

        String sql = jdbc.call(4).sql;
        assertTrue(sql.contains("c.estado = 'PROGRAMADA'"), sql);
        assertTrue(DashboardDao.COUNT_CITAS_PENDIENTES.contains("estado = 'PROGRAMADA'"),
            DashboardDao.COUNT_CITAS_PENDIENTES);
    }

    @Test
    void aNullScopeIsTreatedAsTheGlobalOne() {
        queueScopedAnswers();
        jdbc.rows(FakeJdbc.row("count", 3L))
            .rows(FakeJdbc.row("count", 5L))
            .rows(FakeJdbc.row("count", 9L));
        DashboardSnapshot snapshot = dao.snapshot(null, DAY_START, DAY_END, NOW, 5);

        assertEquals(DashboardDao.COUNT_CITAS_DEL_DIA, jdbc.call(0).sql, "no scope fragment is appended");
        assertEquals(List.of(DAY_START, DAY_END), jdbc.call(0).values());
        assertEquals(8, jdbc.calls.size());
        assertEquals(3, snapshot.medicosActivos());
    }

    @Test
    void theLimitIsClampedBeforeItReachesTheDriver() {
        assertEquals(0, DashboardDao.safeLimit(-1), "PostgreSQL rejects a negative LIMIT");
        assertEquals(0, DashboardDao.safeLimit(0));
        assertEquals(1, DashboardDao.safeLimit(1));
        assertEquals(DashboardDao.MAX_LIMIT, DashboardDao.safeLimit(DashboardDao.MAX_LIMIT));
        assertEquals(DashboardDao.MAX_LIMIT, DashboardDao.safeLimit(Integer.MAX_VALUE));

        queueScopedAnswers();
        jdbc.rows(FakeJdbc.row("count", 3L))
            .rows(FakeJdbc.row("count", 5L))
            .rows(FakeJdbc.row("count", 9L));
        dao.snapshot(Scope.ALL, DAY_START, DAY_END, NOW, -7);
        assertEquals(List.of(NOW, 0), jdbc.call(4).values(), "the clamped value is what gets bound");
    }

    @Test
    void theComposedSqlIsExactlyWhatWeExpect() {
        // the literal text matters: a stray space or a missing alias would silently break the filter
        assertEquals("SELECT count(*) FROM cita WHERE fecha_hora_programada >= ?"
                + " AND fecha_hora_programada < ? AND id_medico = ?",
            DashboardDao.COUNT_CITAS_DEL_DIA + DashboardDao.scopeClause(Scope.medico(10), ""));
        assertEquals(" AND c.id_paciente = ?", DashboardDao.scopeClause(Scope.paciente(20), "c."));
        assertEquals("", DashboardDao.scopeClause(Scope.ALL, "c."), "the global scope adds nothing");
    }
}
