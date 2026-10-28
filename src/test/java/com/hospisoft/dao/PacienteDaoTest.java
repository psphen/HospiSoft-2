package com.hospisoft.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.db.ConnectionPool;
import com.hospisoft.model.Paciente;
import com.hospisoft.model.Page;
import com.hospisoft.service.NotFoundException;
import com.hospisoft.service.ValidationException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PacienteDaoTest {

    private final FakeJdbc jdbc = new FakeJdbc();
    private final PacienteDao dao = new PacienteDao(new ConnectionPool(jdbc::connection, 1, 100, System::currentTimeMillis));

    private static final Paciente MARIA = new Paciente(20L, "2001", "Maria", "Torres", LocalDate.of(1990, 5, 14), "F",
        null, "m@x.co", "", true);

    @Test
    void pageWithoutFiltersAndDateMapping() {
        jdbc.rows(FakeJdbc.row("count", 1L))
            .rows(FakeJdbc.row("id", 20L, "documento", "2001", "nombres", "Maria", "apellidos", "Torres",
                "fecha_nacimiento", LocalDate.of(1990, 5, 14), "sexo", "F", "telefono", null, "correo", "m@x.co",
                "direccion", null, "activo", true));
        Page<Paciente> page = dao.findPage(null, 1, 10);
        assertEquals("SELECT count(*) FROM paciente p", jdbc.call(0).sql);
        assertEquals(PacienteDao.SELECT + PacienteDao.ORDER + " LIMIT ? OFFSET ?", jdbc.call(1).sql);
        assertEquals(List.of(10L, 0L), jdbc.call(1).values());
        assertEquals(LocalDate.of(1990, 5, 14), page.items().get(0).fechaNacimiento());
    }

    @Test
    void scopedPagePutsTheMedicoFirstThenFiltersThenPaging() {
        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findPageAttendedBy(10L, new PacienteFilter("F", true, "a_b"), 3, 10);

        String where = " WHERE " + PacienteDao.ATTENDED_BY + " AND p.sexo = ? AND p.activo = ? AND " + PacienteDao.TEXT_CLAUSE;
        assertEquals("SELECT count(*) FROM paciente p" + where, jdbc.call(0).sql);
        assertTrue(jdbc.call(0).sql.contains("c.estado <> 'CANCELADA'"));
        List<Object> expected = List.of(10L, "F", true, "%a\\_b%", "%a\\_b%", "%a\\_b%", "%a\\_b%");
        assertEquals(expected, jdbc.call(0).values());
        assertEquals(PacienteDao.SELECT + where + PacienteDao.ORDER + " LIMIT ? OFFSET ?", jdbc.call(1).sql);
        List<Object> withPaging = new ArrayList<>(expected);
        withPaging.add(10L);
        withPaging.add(20L);
        assertEquals(withPaging, jdbc.call(1).values());
    }

    @Test
    void singleFilterClauses() {
        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findPage(new PacienteFilter("M", null, null), 1, 10);
        assertEquals("SELECT count(*) FROM paciente p WHERE p.sexo = ?", jdbc.call(0).sql);
        assertEquals(List.of("M"), jdbc.call(0).values());

        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findPage(new PacienteFilter(null, false, null), 1, 10);
        assertEquals("SELECT count(*) FROM paciente p WHERE p.activo = ?", jdbc.call(2).sql);

        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findPage(new PacienteFilter(null, null, "x"), 1, 10);
        assertEquals("SELECT count(*) FROM paciente p WHERE " + PacienteDao.TEXT_CLAUSE, jdbc.call(4).sql);
        assertEquals(4, jdbc.call(4).params.size());
    }

    @Test
    void scopedFindByIdBindsIdThenMedicoAndUsesTheNonCancelledRule() {
        jdbc.rows();
        assertTrue(dao.findByIdAttendedBy(20, 10).isEmpty());
        assertEquals(PacienteDao.SELECT + " WHERE p.id = ? AND " + PacienteDao.ATTENDED_BY, jdbc.call(0).sql);
        assertTrue(jdbc.call(0).sql.contains("<> 'CANCELADA'"));
        assertEquals(List.of(20L, 10L), jdbc.call(0).values());
    }

    @Test
    void findByIdAndDocumento() {
        jdbc.rows();
        dao.findById(20);
        assertEquals(PacienteDao.SELECT + " WHERE p.id = ?", jdbc.call(0).sql);
        assertEquals(List.of(20L), jdbc.call(0).values());
        jdbc.rows();
        dao.findByDocumento("2001");
        assertEquals(PacienteDao.SELECT + " WHERE p.documento = ?", jdbc.call(1).sql);
        assertEquals(List.of("2001"), jdbc.call(1).values());
    }

    @Test
    void insertBindsNineParametersWithLocalDateAndNulls() {
        jdbc.rows(FakeJdbc.row("id", 77L));
        assertEquals(77, dao.insert(MARIA));
        FakeJdbc.Call call = jdbc.call(0);
        assertEquals(PacienteDao.INSERT, call.sql);
        assertEquals(9, call.params.size());
        assertEquals(LocalDate.of(1990, 5, 14), call.params.get(4));
        assertEquals("F", call.params.get(5));
        assertEquals(Set.of(6), call.nulls);
        assertEquals("", call.params.get(8));
        assertEquals(true, call.params.get(9));
    }

    @Test
    void updateBindsIdTenthAndChecksAffectedRows() {
        jdbc.update(1);
        dao.update(MARIA);
        assertEquals(PacienteDao.UPDATE, jdbc.call(0).sql);
        assertEquals(10, jdbc.call(0).params.size());
        assertEquals(20L, jdbc.call(0).params.get(10));
        jdbc.update(0);
        assertThrows(NotFoundException.class, () -> dao.update(MARIA));
    }

    @Test
    void constraintViolationsAreTyped() {
        jdbc.fail("23505", "duplicate key Key (documento)=(2001)");
        ValidationException ex = assertThrows(ValidationException.class, () -> dao.insert(MARIA));
        assertEquals("Ya existe un registro con este documento", ex.errors().get("documento"));
        jdbc.fail("23503", "fk");
        assertThrows(HasHistoryException.class, () -> dao.delete(20));
        jdbc.update(0);
        assertThrows(NotFoundException.class, () -> dao.delete(20));
        jdbc.update(0);
        assertThrows(NotFoundException.class, () -> dao.setActivo(20, false));
    }

    @Test
    void activeForSelectFiltersAndOrders() {
        jdbc.rows();
        assertTrue(dao.findActiveForSelect().isEmpty());
        assertEquals(PacienteDao.SELECT + " WHERE p.activo = ?" + PacienteDao.ORDER, jdbc.call(0).sql);
        assertEquals(List.of(true), jdbc.call(0).values());
    }

    @Test
    void hasAssociatedHistoryBindsTheIdThreeTimes() {
        jdbc.rows(FakeJdbc.row("exists", true));
        assertTrue(dao.hasAssociatedHistory(20));
        assertEquals(PacienteDao.HAS_HISTORY, jdbc.call(0).sql);
        assertEquals(List.of(20L, 20L, 20L), jdbc.call(0).values());
    }
}
