package com.hospisoft.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.db.ConnectionPool;
import com.hospisoft.model.Cita;
import com.hospisoft.model.CitaView;
import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.Page;
import com.hospisoft.service.CitaService;
import com.hospisoft.service.ConflictException;
import com.hospisoft.service.NotFoundException;
import com.hospisoft.service.ValidationException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CitaDaoTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 10, 27, 9, 0, 0, 0, ZoneOffset.ofHours(-5));
    private static final OffsetDateTime DAY_START = OffsetDateTime.of(2026, 10, 27, 0, 0, 0, 0, ZoneOffset.ofHours(-5));
    private static final OffsetDateTime DAY_END = DAY_START.plusDays(1);

    private final FakeJdbc jdbc = new FakeJdbc();
    private final CitaDao dao = new CitaDao(new ConnectionPool(jdbc::connection, 1, 100, System::currentTimeMillis));

    private static Map<String, Object> row(long id, String estado) {
        return FakeJdbc.row("id", id, "id_medico", 10L, "id_paciente", 20L, "fecha_hora_programada", T0,
            "motivo", "Control", "estado", estado, "hora_inicio_real", null, "hora_fin_real", null,
            "observaciones", null, "medico_nombres", "Carlos", "medico_apellidos", "Garcia",
            "especialidad", "Medicina General", "paciente_nombres", "Maria", "paciente_apellidos", "Torres",
            "paciente_documento", "2001");
    }

    @Test
    void pageWithoutFiltersJoinsAndMapsTimestamps() {
        jdbc.rows(FakeJdbc.row("count", 1L)).rows(row(1, "PROGRAMADA"));
        Page<CitaView> page = dao.findPage(null, 1, 10);
        assertEquals(CitaDao.COUNT, jdbc.call(0).sql);
        assertTrue(CitaDao.COUNT.contains("JOIN medico m ON m.id = c.id_medico JOIN paciente p ON p.id = c.id_paciente"));
        assertEquals(CitaDao.SELECT + CitaDao.ORDER + " LIMIT ? OFFSET ?", jdbc.call(1).sql);
        assertEquals(List.of(10L, 0L), jdbc.call(1).values());
        CitaView view = page.items().get(0);
        assertEquals(T0, view.cita().fechaHoraProgramada());
        assertEquals(EstadoCita.PROGRAMADA, view.cita().estado());
        assertEquals("Carlos Garcia", view.medicoNombre());
        assertEquals("Maria Torres", view.pacienteNombre());
        assertEquals("2001", view.pacienteDocumento());
    }

    @Test
    void everyFilterClauseAndParameterOrder() {
        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        OffsetDateTime from = T0.minusDays(1);
        CitaQuery q = new CitaQuery("tor", DAY_START, DAY_END, EstadoCita.PROGRAMADA, 10L, 20L, from, null);
        dao.findPage(q, 2, 5);
        String where = " WHERE " + CitaDao.TEXT_CLAUSE
            + " AND c.fecha_hora_programada >= ? AND c.fecha_hora_programada < ?"
            + " AND c.estado = ? AND c.id_medico = ? AND c.id_paciente = ? AND c.fecha_hora_programada >= ?";
        assertEquals(CitaDao.COUNT + where, jdbc.call(0).sql);
        List<Object> values = jdbc.call(0).values();
        assertEquals(13, values.size());
        for (int i = 0; i < 7; i++) {
            assertEquals("%tor%", values.get(i));
        }
        assertEquals(List.of(DAY_START, DAY_END, "PROGRAMADA", 10L, 20L, from), values.subList(7, 13));
        assertEquals(CitaDao.SELECT + where + CitaDao.ORDER + " LIMIT ? OFFSET ?", jdbc.call(1).sql);
        assertEquals(List.of(5L, 5L), jdbc.call(1).values().subList(13, 15));

        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findPage(new CitaQuery(null, null, null, null, null, null, null, T0), 1, 10);
        assertEquals(CitaDao.COUNT + " WHERE c.fecha_hora_programada < ?", jdbc.call(2).sql);
        assertEquals(List.of(T0), jdbc.call(2).values());
    }

    @Test
    void findByIdAndAgenda() {
        jdbc.rows();
        assertTrue(dao.findById(7).isEmpty());
        assertEquals(CitaDao.SELECT + " WHERE c.id = ?", jdbc.call(0).sql);
        assertEquals(List.of(7L), jdbc.call(0).values());

        jdbc.rows(row(1, "PROGRAMADA"), row(2, "CANCELADA"));
        List<CitaView> day = dao.findByMedicoAndDay(10, DAY_START, DAY_END);
        assertEquals(2, day.size());
        assertEquals(CitaDao.AGENDA, jdbc.call(1).sql);
        assertEquals(List.of(10L, DAY_START, DAY_END), jdbc.call(1).values());
    }

    @Test
    void cancelOnlyWhileProgramada() {
        jdbc.update(1);
        dao.cancel(5);
        assertEquals(CitaDao.CANCEL, jdbc.call(0).sql);
        assertEquals(List.of(5L), jdbc.call(0).values());
        jdbc.update(0).rows(FakeJdbc.row("exists", true));
        assertThrows(ConflictException.class, () -> dao.cancel(5));
        jdbc.update(0).rows(FakeJdbc.row("exists", false));
        assertThrows(NotFoundException.class, () -> dao.cancel(5));
    }

    @Test
    void transactionLocksChecksAndInserts() {
        jdbc.rows(FakeJdbc.row("activo", true))       // lock medico
            .rows(FakeJdbc.row("activo", false))      // lock paciente (inactive, reported to the caller)
            .rows(FakeJdbc.row("exists", false))      // medico overlap
            .rows(FakeJdbc.row("exists", false))      // paciente overlap
            .rows(FakeJdbc.row("id", 99L));           // insert
        Cita cita = new Cita(null, 10L, 20L, T0, "Control", EstadoCita.PROGRAMADA, null, null, null);
        long id = dao.inTransaction(tx -> {
            assertEquals(java.util.Optional.of(true), tx.lockMedico(10));
            assertEquals(java.util.Optional.of(false), tx.lockPaciente(20));
            assertFalse(tx.existsOverlapForMedico(10, T0, T0.plusMinutes(30), null));
            assertFalse(tx.existsOverlapForPaciente(20, T0, T0.plusMinutes(30), 4L));
            return tx.insert(cita);
        });
        assertEquals(99, id);
        assertEquals(List.of("setAutoCommit(true)", "setAutoCommit(false)", "commit", "setAutoCommit(true)"), jdbc.events);
        assertEquals(CitaDao.LOCK_MEDICO, jdbc.call(0).sql);
        assertEquals("SELECT activo FROM medico WHERE id = ? FOR NO KEY UPDATE", CitaDao.LOCK_MEDICO);
        assertEquals(List.of(10L), jdbc.call(0).values());
        assertEquals("SELECT activo FROM paciente WHERE id = ? FOR NO KEY UPDATE", jdbc.call(1).sql);
        assertEquals(List.of(20L), jdbc.call(1).values());
        assertEquals(CitaDao.OVERLAP_MEDICO, jdbc.call(2).sql);
        assertEquals(List.of(10L, -1L, T0.plusMinutes(30), CitaService.SLOT_MINUTES, T0), jdbc.call(2).values());
        assertEquals(CitaDao.OVERLAP_PACIENTE, jdbc.call(3).sql);
        assertEquals(List.of(20L, 4L, T0.plusMinutes(30), CitaService.SLOT_MINUTES, T0), jdbc.call(3).values());
        assertTrue(CitaDao.OVERLAP_MEDICO.contains("estado <> 'CANCELADA'"));
        assertEquals(CitaDao.INSERT, jdbc.call(4).sql);
        assertEquals(List.of(10L, 20L, T0, "Control"), jdbc.call(4).values());
        assertTrue(CitaDao.INSERT.contains("'PROGRAMADA'"));
    }

    @Test
    void lockReportsMissingRowsAndRollsBackOnBusinessErrors() {
        jdbc.rows();
        assertThrows(IllegalStateException.class, () -> dao.inTransaction(tx -> {
            assertTrue(tx.lockMedico(10).isEmpty());
            throw new IllegalStateException("business rule");
        }));
        assertEquals(List.of("setAutoCommit(true)", "setAutoCommit(false)", "rollback", "setAutoCommit(true)"), jdbc.events);
    }

    @Test
    void foreignKeyViolationsBecomeFieldErrors() {
        Cita cita = new Cita(null, 10L, 20L, T0, "Control", EstadoCita.PROGRAMADA, null, null, null);
        jdbc.fail("23503", "insert or update on table \"cita\" violates foreign key constraint \"cita_id_paciente_fkey\"");
        ValidationException paciente = assertThrows(ValidationException.class, () -> dao.inTransaction(tx -> tx.insert(cita)));
        assertEquals("No existe", paciente.errors().get("idPaciente"));
        assertNull(paciente.errors().get("idMedico"));
        jdbc.fail("23503", "violates foreign key constraint \"cita_id_medico_fkey\"");
        ValidationException medico = assertThrows(ValidationException.class, () -> dao.inTransaction(tx -> {
            tx.reschedule(5, 10, 20, T0, "Control");
            return null;
        }));
        assertEquals("No existe", medico.errors().get("idMedico"));
        jdbc.fail("23503", "unknown constraint");
        ValidationException both = assertThrows(ValidationException.class, () -> dao.inTransaction(tx -> tx.insert(cita)));
        assertEquals(2, both.errors().size());
    }

    @Test
    void rescheduleGuardsTheState() {
        jdbc.update(1);
        dao.inTransaction(tx -> {
            tx.reschedule(5, 10, 20, T0, "Control");
            return null;
        });
        assertEquals(CitaDao.RESCHEDULE, jdbc.call(0).sql);
        assertTrue(CitaDao.RESCHEDULE.contains("WHERE id = ? AND estado = 'PROGRAMADA'"));
        assertEquals(List.of(10L, 20L, T0, "Control", 5L), jdbc.call(0).values());

        jdbc.update(0).rows(FakeJdbc.row("exists", true));
        assertThrows(ConflictException.class, () -> dao.inTransaction(tx -> {
            tx.reschedule(5, 10, 20, T0, "Control");
            return null;
        }));
        jdbc.update(0).rows(FakeJdbc.row("exists", false));
        assertThrows(NotFoundException.class, () -> dao.inTransaction(tx -> {
            tx.reschedule(5, 10, 20, T0, "Control");
            return null;
        }));
    }
}
