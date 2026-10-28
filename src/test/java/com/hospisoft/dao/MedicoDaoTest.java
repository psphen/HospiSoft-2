package com.hospisoft.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.db.ConnectionPool;
import com.hospisoft.model.Medico;
import com.hospisoft.model.Page;
import com.hospisoft.service.NotFoundException;
import com.hospisoft.service.ValidationException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MedicoDaoTest {

    private final FakeJdbc jdbc = new FakeJdbc();
    private final MedicoDao dao = new MedicoDao(new ConnectionPool(jdbc::connection, 1, 100, System::currentTimeMillis));

    private static final String WHERE_ALL =
        " WHERE especialidad = ? AND activo = ? AND " + MedicoDao.TEXT_CLAUSE;

    @Test
    void pageWithoutFiltersUsesLimitAndOffset() {
        jdbc.rows(FakeJdbc.row("count", 12L))
            .rows(FakeJdbc.row("id", 1L, "documento", "1001", "nombres", "Ana", "apellidos", "Mora",
                "especialidad", "Pediatria", "telefono", null, "correo", "a@x.co", "activo", true));
        Page<Medico> page = dao.findPage(MedicoFilter.NONE, 1, 10);

        assertEquals("SELECT count(*) FROM medico", jdbc.call(0).sql);
        assertEquals(List.of(), jdbc.call(0).values());
        assertEquals(MedicoDao.SELECT + MedicoDao.ORDER + " LIMIT ? OFFSET ?", jdbc.call(1).sql);
        assertEquals(List.of(10L, 0L), jdbc.call(1).values());
        assertEquals(12, page.total());
        assertEquals(1, page.items().size());
        assertEquals("Ana Mora", page.items().get(0).nombreCompleto());
        assertEquals(null, page.items().get(0).telefono());
    }

    @Test
    void pageThreeOffsetsTwenty() {
        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findPage(null, 3, 10);
        assertEquals(List.of(10L, 20L), jdbc.call(1).values());
    }

    @Test
    void everyFilterAddsItsClauseAndParametersInOrder() {
        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findPage(new MedicoFilter("Pediatria", true, "50%"), 2, 5);

        assertEquals("SELECT count(*) FROM medico" + WHERE_ALL, jdbc.call(0).sql);
        List<Object> expected = List.of("Pediatria", true, "%50\\%%", "%50\\%%", "%50\\%%", "%50\\%%");
        assertEquals(expected, jdbc.call(0).values());
        assertEquals(MedicoDao.SELECT + WHERE_ALL + MedicoDao.ORDER + " LIMIT ? OFFSET ?", jdbc.call(1).sql);
        List<Object> withPaging = new java.util.ArrayList<>(expected);
        withPaging.add(5L);
        withPaging.add(5L);
        assertEquals(withPaging, jdbc.call(1).values());
    }

    @Test
    void singleFiltersProduceSingleClauses() {
        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findPage(new MedicoFilter(null, false, null), 1, 10);
        assertEquals("SELECT count(*) FROM medico WHERE activo = ?", jdbc.call(0).sql);
        assertEquals(List.of(false), jdbc.call(0).values());

        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findPage(new MedicoFilter(null, null, "ana"), 1, 10);
        assertEquals("SELECT count(*) FROM medico WHERE " + MedicoDao.TEXT_CLAUSE, jdbc.call(2).sql);
        assertEquals(List.of("%ana%", "%ana%", "%ana%", "%ana%"), jdbc.call(2).values());

        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findPage(new MedicoFilter("Cardiologia", null, null), 1, 10);
        assertEquals(List.of("Cardiologia"), jdbc.call(4).values());
    }

    @Test
    void findByIdAndDocumento() {
        jdbc.rows();
        assertTrue(dao.findById(7).isEmpty());
        assertEquals(MedicoDao.SELECT + " WHERE id = ?", jdbc.call(0).sql);
        assertEquals(List.of(7L), jdbc.call(0).values());

        jdbc.rows(FakeJdbc.row("id", 7L, "documento", "1001", "nombres", "A", "apellidos", "B",
            "especialidad", "C", "telefono", "t", "correo", "c", "activo", false));
        Optional<Medico> found = dao.findByDocumento("1001");
        assertEquals(MedicoDao.SELECT + " WHERE documento = ?", jdbc.call(1).sql);
        assertEquals(List.of("1001"), jdbc.call(1).values());
        assertFalse(found.orElseThrow().activo());
    }

    @Test
    void insertBindsSevenParametersAndReadsReturningId() {
        jdbc.rows(FakeJdbc.row("id", 42L));
        long id = dao.insert(new Medico(null, "1001", "Ana", "Mora", "Pediatria", null, "", true));
        assertEquals(42, id);
        assertEquals(MedicoDao.INSERT, jdbc.call(0).sql);
        assertEquals(7, jdbc.call(0).params.size());
        assertEquals(List.of("1001", "Ana", "Mora", "Pediatria"), jdbc.call(0).values().subList(0, 4));
        assertEquals(Set.of(5), jdbc.call(0).nulls);
        assertEquals("", jdbc.call(0).params.get(6));
        assertEquals(true, jdbc.call(0).params.get(7));
    }

    @Test
    void updateBindsIdLastAndChecksAffectedRows() {
        jdbc.update(1);
        dao.update(new Medico(9L, "1001", "Ana", "Mora", "Pediatria", "300", "a@x.co", false));
        assertEquals(MedicoDao.UPDATE, jdbc.call(0).sql);
        assertEquals(List.of("1001", "Ana", "Mora", "Pediatria", "300", "a@x.co", false, 9L), jdbc.call(0).values());

        jdbc.update(0);
        assertThrows(NotFoundException.class,
            () -> dao.update(new Medico(9L, "1001", "Ana", "Mora", "Pediatria", null, null, true)));
    }

    @Test
    void uniqueViolationBecomesValidationError() {
        jdbc.fail("23505", "duplicate key value violates unique constraint Key (documento)=(1001)");
        ValidationException insert = assertThrows(ValidationException.class,
            () -> dao.insert(new Medico(null, "1001", "A", "B", "C", null, null, true)));
        assertEquals("Ya existe un registro con este documento", insert.errors().get("documento"));

        jdbc.fail("23505", "duplicate");
        assertThrows(ValidationException.class,
            () -> dao.update(new Medico(1L, "1001", "A", "B", "C", null, null, true)));

        jdbc.fail("42P01", "relation does not exist");
        assertThrows(IllegalStateException.class,
            () -> dao.insert(new Medico(null, "1001", "A", "B", "C", null, null, true)));
    }

    @Test
    void deleteMapsForeignKeyViolationAndAffectedRows() {
        jdbc.update(1);
        dao.delete(5);
        assertEquals(MedicoDao.DELETE, jdbc.call(0).sql);
        assertEquals(List.of(5L), jdbc.call(0).values());

        jdbc.fail("23503", "violates foreign key constraint");
        assertThrows(HasHistoryException.class, () -> dao.delete(5));

        jdbc.update(0);
        assertThrows(NotFoundException.class, () -> dao.delete(5));
    }

    @Test
    void setActivoChecksAffectedRows() {
        jdbc.update(1);
        dao.setActivo(5, false);
        assertEquals(MedicoDao.SET_ACTIVO, jdbc.call(0).sql);
        assertEquals(List.of(false, 5L), jdbc.call(0).values());
        jdbc.update(0);
        assertThrows(NotFoundException.class, () -> dao.setActivo(5, true));
    }

    @Test
    void hasAssociatedHistoryBindsTheIdThreeTimes() {
        jdbc.rows(FakeJdbc.row("exists", true));
        assertTrue(dao.hasAssociatedHistory(3));
        assertEquals(MedicoDao.HAS_HISTORY, jdbc.call(0).sql);
        assertEquals(List.of(3L, 3L, 3L), jdbc.call(0).values());
        jdbc.rows(FakeJdbc.row("exists", false));
        assertFalse(dao.hasAssociatedHistory(3));
    }

    @Test
    void activeForSelectFiltersAndOrders() {
        jdbc.rows(FakeJdbc.row("id", 1L, "documento", "1001", "nombres", "Ana", "apellidos", "Mora",
            "especialidad", "Pediatria", "telefono", null, "correo", null, "activo", true));
        List<Medico> active = dao.findActiveForSelect();
        assertEquals(MedicoDao.SELECT + " WHERE activo = ?" + MedicoDao.ORDER, jdbc.call(0).sql);
        assertEquals(List.of(true), jdbc.call(0).values());
        assertEquals("Mora", active.get(0).apellidos());
    }

    @Test
    void especialidadesAreReadInOrder() {
        jdbc.rows(FakeJdbc.row("especialidad", "Cardiologia"), FakeJdbc.row("especialidad", "Pediatria"));
        assertEquals(List.of("Cardiologia", "Pediatria"), dao.findEspecialidades());
        assertEquals(MedicoDao.ESPECIALIDADES, jdbc.call(0).sql);
    }
}
