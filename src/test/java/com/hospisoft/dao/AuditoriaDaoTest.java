package com.hospisoft.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.db.ConnectionPool;
import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.Auditoria;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.model.Page;
import com.hospisoft.model.Rol;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuditoriaDaoTest {

    private static final OffsetDateTime FROM = OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-5));
    private static final OffsetDateTime TO = FROM.plusDays(4);

    private final FakeJdbc jdbc = new FakeJdbc();
    private final AuditoriaDao dao = new AuditoriaDao(new ConnectionPool(jdbc::connection, 1, 100, System::currentTimeMillis));

    @Test
    void pageWithoutFiltersIsNewestFirstAndMapsNullableColumns() {
        OffsetDateTime fecha = OffsetDateTime.of(2026, 11, 4, 15, 30, 0, 0, ZoneOffset.UTC);
        jdbc.rows(FakeJdbc.row("count", 2L))
            .rows(FakeJdbc.row("id", 9L, "fecha", fecha, "id_usuario", 1L, "username", "admin", "rol", "ADMIN",
                    "accion", "CREAR", "modulo", "PACIENTE", "id_registro", 20L, "detalle", "Paciente 2001 - Maria Torres"),
                FakeJdbc.row("id", 8L, "fecha", fecha.minusMinutes(1), "id_usuario", null, "username", "ghost",
                    "rol", null, "accion", "LOGIN_FALLIDO", "modulo", "SESION", "id_registro", null, "detalle", null));

        Page<Auditoria> page = dao.findPage(null, 1, 10);

        assertEquals("SELECT count(*) FROM auditoria a", jdbc.call(0).sql);
        assertEquals(AuditoriaDao.SELECT + AuditoriaDao.ORDER + " LIMIT ? OFFSET ?", jdbc.call(1).sql);
        assertTrue(AuditoriaDao.ORDER.contains("a.fecha DESC, a.id DESC"));
        assertEquals(List.of(10L, 0L), jdbc.call(1).values());
        assertEquals(2, page.total());

        Auditoria first = page.items().get(0);
        assertEquals(new Auditoria(9L, fecha, 1L, "admin", Rol.ADMIN, AccionAuditoria.CREAR, ModuloAuditoria.PACIENTE,
            20L, "Paciente 2001 - Maria Torres"), first);
        Auditoria failed = page.items().get(1);
        assertNull(failed.idUsuario());
        assertNull(failed.rol());
        assertNull(failed.idRegistro());
        assertNull(failed.detalle());
        assertEquals(AccionAuditoria.LOGIN_FALLIDO, failed.accion());
    }

    @Test
    void everyFilterIsAParameterInAFixedOrder() {
        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findPage(new AuditoriaQuery(" m_a ", AccionAuditoria.EDITAR, ModuloAuditoria.CITA, FROM, TO), 2, 5);

        String where = " WHERE " + AuditoriaDao.TEXT_CLAUSE
            + " AND a.accion = ? AND a.modulo = ? AND a.fecha >= ? AND a.fecha < ?";
        assertEquals(AuditoriaDao.COUNT + where, jdbc.call(0).sql);
        List<Object> expected = List.of("%m\\_a%", "%m\\_a%", "EDITAR", "CITA", FROM, TO);
        assertEquals(expected, jdbc.call(0).values());
        assertEquals(AuditoriaDao.SELECT + where + AuditoriaDao.ORDER + " LIMIT ? OFFSET ?", jdbc.call(1).sql);
        List<Object> withPaging = new ArrayList<>(expected);
        withPaging.add(5L);
        withPaging.add(5L);
        assertEquals(withPaging, jdbc.call(1).values());
    }

    @Test
    void singleBoundsAndTextOnly() {
        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findPage(new AuditoriaQuery(null, null, null, FROM, null), 1, 10);
        assertEquals(AuditoriaDao.COUNT + " WHERE a.fecha >= ?", jdbc.call(0).sql);

        jdbc.rows(FakeJdbc.row("count", 0L)).rows();
        dao.findPage(new AuditoriaQuery("  ", null, null, null, TO), 1, 10);
        assertEquals(AuditoriaDao.COUNT + " WHERE a.fecha < ?", jdbc.call(2).sql, "blank text is no filter");
        assertTrue(AuditoriaDao.TEXT_CLAUSE.contains("a.username ILIKE ? ESCAPE '\\'"));
        assertTrue(AuditoriaDao.TEXT_CLAUSE.contains("a.detalle ILIKE ? ESCAPE '\\'"));
    }

    @Test
    void insertBindsSevenParametersAndLetsTheDatabaseStampTheDate() {
        jdbc.update(1);
        dao.insert(new Auditoria(null, null, 1L, "admin", Rol.ADMIN, AccionAuditoria.ELIMINAR, ModuloAuditoria.MEDICO,
            7L, "Médico 1001 - Carlos Garcia"));
        FakeJdbc.Call call = jdbc.call(0);
        assertEquals(AuditoriaDao.INSERT, call.sql);
        assertTrue(!call.sql.contains("fecha"), "fecha comes from DEFAULT now()");
        assertEquals(List.of(1L, "admin", "ADMIN", "ELIMINAR", "MEDICO", 7L, "Médico 1001 - Carlos Garcia"), call.values());
        assertTrue(call.nulls.isEmpty());
    }

    @Test
    void insertOfAFailedLoginBindsNulls() {
        jdbc.update(1);
        dao.insert(new Auditoria(null, null, null, "ghost", null, AccionAuditoria.LOGIN_FALLIDO, ModuloAuditoria.SESION,
            null, null));
        FakeJdbc.Call call = jdbc.call(0);
        assertEquals(7, call.params.size());
        assertEquals(Set.of(1, 3, 6, 7), call.nulls);
        assertEquals("ghost", call.params.get(2));
        assertEquals("LOGIN_FALLIDO", call.params.get(4));
        assertEquals("SESION", call.params.get(5));
    }
}
