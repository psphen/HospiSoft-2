package com.hospisoft.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.db.ConnectionPool;
import com.hospisoft.model.Medicamento;
import com.hospisoft.model.Page;
import com.hospisoft.model.StockState;
import com.hospisoft.service.ConflictException;
import com.hospisoft.service.NotFoundException;
import com.hospisoft.service.ValidationException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MedicamentoDaoTest {

    private final FakeJdbc jdbc = new FakeJdbc();
    private final MedicamentoDao dao =
        new MedicamentoDao(new ConnectionPool(jdbc::connection, 1, 100, System::currentTimeMillis));

    private static final Medicamento MED = new Medicamento(5L, "MED-001", "Acetaminofen", null, "Caja x 20", 150, 30, true);

    @Test
    void pageWithoutFilters() {
        jdbc.rows(FakeJdbc.row("count", 1L))
            .rows(FakeJdbc.row("id", 5L, "codigo", "MED-001", "nombre", "Acetaminofen", "descripcion", null,
                "presentacion", "Caja x 20", "stock", 150, "stock_minimo", 30, "activo", true));
        Page<Medicamento> page = dao.findPage(null, 1, 10);
        assertEquals("SELECT count(*) FROM medicamento", jdbc.call(0).sql);
        assertEquals(MedicamentoDao.SELECT + MedicamentoDao.ORDER + " LIMIT ? OFFSET ?", jdbc.call(1).sql);
        assertEquals(List.of(10L, 0L), jdbc.call(1).values());
        assertEquals(StockState.DISPONIBLE, page.items().get(0).stockState());
    }

    @Test
    void everyFilterInOrderAndStockStateIsAConstantFragment() {
        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findPage(new MedicamentoFilter("ace", true, StockState.STOCK_BAJO), 2, 5);
        String where = " WHERE " + MedicamentoDao.TEXT_CLAUSE + " AND activo = ? AND stock > 0 AND stock <= stock_minimo";
        assertEquals("SELECT count(*) FROM medicamento" + where, jdbc.call(0).sql);
        assertEquals(List.of("%ace%", "%ace%", "%ace%", true), jdbc.call(0).values());
        assertEquals(MedicamentoDao.SELECT + where + MedicamentoDao.ORDER + " LIMIT ? OFFSET ?", jdbc.call(1).sql);
        assertEquals(List.of("%ace%", "%ace%", "%ace%", true, 5L, 5L), jdbc.call(1).values());
    }

    @Test
    void stockStateFragments() {
        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findPage(new MedicamentoFilter(null, null, StockState.AGOTADO), 1, 10);
        assertEquals("SELECT count(*) FROM medicamento WHERE stock = 0", jdbc.call(0).sql);
        assertEquals(List.of(), jdbc.call(0).values());
        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findPage(new MedicamentoFilter(null, null, StockState.DISPONIBLE), 1, 10);
        assertEquals("SELECT count(*) FROM medicamento WHERE stock > stock_minimo", jdbc.call(2).sql);
        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findPage(new MedicamentoFilter(null, false, null), 1, 10);
        assertEquals("SELECT count(*) FROM medicamento WHERE activo = ?", jdbc.call(4).sql);
        assertEquals(List.of(false), jdbc.call(4).values());
    }

    @Test
    void findByIdAndCodigo() {
        jdbc.rows();
        assertTrue(dao.findById(5).isEmpty());
        assertEquals(MedicamentoDao.SELECT + " WHERE id = ?", jdbc.call(0).sql);
        assertEquals(List.of(5L), jdbc.call(0).values());
        jdbc.rows();
        dao.findByCodigo("MED-001");
        assertEquals(MedicamentoDao.SELECT + " WHERE codigo = ?", jdbc.call(1).sql);
        assertEquals(List.of("MED-001"), jdbc.call(1).values());
    }

    @Test
    void insertBindsSevenParametersAndReturnsId() {
        jdbc.rows(FakeJdbc.row("id", 9L));
        assertEquals(9, dao.insert(MED));
        assertEquals(MedicamentoDao.INSERT, jdbc.call(0).sql);
        assertEquals(7, jdbc.call(0).params.size());
        assertEquals(Set.of(3), jdbc.call(0).nulls);
        assertEquals(150, jdbc.call(0).params.get(5));
        assertEquals(30, jdbc.call(0).params.get(6));
    }

    @Test
    void updateIsOptimisticOnStock() {
        jdbc.update(1);
        dao.update(MED, 150);
        assertEquals(MedicamentoDao.UPDATE, jdbc.call(0).sql);
        assertTrue(MedicamentoDao.UPDATE.contains("WHERE id = ? AND stock = ?"));
        assertEquals(9, jdbc.call(0).params.size());
        assertEquals(5L, jdbc.call(0).params.get(8));
        assertEquals(150, jdbc.call(0).params.get(9));
        assertEquals(1, jdbc.calls.size(), "no existence query when the update applied");

        // 0 rows and the row exists -> CONFLICT (same connection, second statement)
        jdbc.update(0).rows(FakeJdbc.row("exists", true));
        assertThrows(ConflictException.class, () -> dao.update(MED, 150));
        assertEquals(MedicamentoDao.EXISTS_BY_ID, jdbc.call(2).sql);
        assertEquals(List.of(5L), jdbc.call(2).values());

        // 0 rows and the row is gone -> NOT_FOUND
        jdbc.update(0).rows(FakeJdbc.row("exists", false));
        assertThrows(NotFoundException.class, () -> dao.update(MED, 150));

        jdbc.fail("23505", "duplicate");
        assertThrows(ValidationException.class, () -> dao.update(MED, 150));
    }

    @Test
    void constraintViolationsAreTyped() {
        jdbc.fail("23505", "duplicate key Key (codigo)=(MED-001)");
        ValidationException ex = assertThrows(ValidationException.class, () -> dao.insert(MED));
        assertEquals("Ya existe un registro con este código", ex.errors().get("codigo"));
        jdbc.fail("23503", "fk");
        assertThrows(HasHistoryException.class, () -> dao.delete(5));
        jdbc.update(0);
        assertThrows(NotFoundException.class, () -> dao.delete(5));
        jdbc.update(0);
        assertThrows(NotFoundException.class, () -> dao.setActivo(5, false));
    }

    @Test
    void historyLowStockAndActiveQueries() {
        jdbc.rows(FakeJdbc.row("exists", true));
        assertTrue(dao.hasAssociatedHistory(5));
        assertEquals(MedicamentoDao.HAS_HISTORY, jdbc.call(0).sql);
        assertEquals(List.of(5L), jdbc.call(0).values());

        jdbc.rows(FakeJdbc.row("count", 3L));
        assertEquals(3, dao.countLowStock());
        assertEquals("SELECT count(*) FROM medicamento WHERE stock <= stock_minimo AND activo", jdbc.call(1).sql);

        jdbc.rows(FakeJdbc.row("id", 5L, "codigo", "MED-001", "nombre", "A", "descripcion", null,
            "presentacion", "P", "stock", 1, "stock_minimo", 2, "activo", true));
        List<Medicamento> active = dao.findAllActive();
        assertEquals(MedicamentoDao.SELECT + " WHERE activo = ?" + MedicamentoDao.ORDER, jdbc.call(2).sql);
        assertEquals(List.of(true), jdbc.call(2).values());
        assertEquals(StockState.STOCK_BAJO, active.get(0).stockState());
    }
}
