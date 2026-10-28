package com.hospisoft.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.db.ConnectionPool;
import com.hospisoft.model.Rol;
import com.hospisoft.model.Usuario;
import java.util.List;
import org.junit.jupiter.api.Test;

class UsuarioDaoTest {

    private final FakeJdbc jdbc = new FakeJdbc();
    private final UsuarioDao dao = new UsuarioDao(new ConnectionPool(jdbc::connection, 1, 100, System::currentTimeMillis));

    @Test
    void effectiveActivoRequiresTheLinkedRowToBeActive() {
        assertTrue(UsuarioDao.FIND_BY_USERNAME.contains("(u.activo AND COALESCE(m.activo, p.activo, TRUE)) AS activo"));
        assertTrue(UsuarioDao.FIND_BY_USERNAME.contains("LEFT JOIN medico m ON u.rol = 'MEDICO' AND m.id = u.id_referencia"));
        assertTrue(UsuarioDao.FIND_BY_USERNAME.contains("LEFT JOIN paciente p ON u.rol = 'PACIENTE' AND p.id = u.id_referencia"));
        assertTrue(UsuarioDao.FIND_BY_USERNAME.trim().endsWith("WHERE u.username = ?"));
    }

    @Test
    void bindsUsernameAndMapsTheRow() {
        jdbc.rows(FakeJdbc.row("id", 2L, "username", "dr.garcia", "password_hash", "h", "salt", "s", "rol", "MEDICO",
            "activo", false, "id_referencia", 10L, "display_name", "Carlos Garcia"));
        Usuario usuario = dao.findByUsername("dr.garcia").orElseThrow();
        assertEquals(UsuarioDao.FIND_BY_USERNAME, jdbc.call(0).sql);
        assertEquals(List.of("dr.garcia"), jdbc.call(0).values());
        assertEquals(Rol.MEDICO, usuario.rol());
        assertFalse(usuario.activo());
        assertEquals(10L, usuario.idReferencia());
        assertEquals("Carlos Garcia", usuario.displayName());
    }

    @Test
    void adminWithoutReferenceMapsNull() {
        jdbc.rows(FakeJdbc.row("id", 1L, "username", "admin", "password_hash", "h", "salt", "s", "rol", "ADMIN",
            "activo", true, "id_referencia", null, "display_name", null));
        Usuario usuario = dao.findByUsername("admin").orElseThrow();
        assertNull(usuario.idReferencia());
        assertNull(usuario.displayName());
        assertTrue(usuario.activo());
        jdbc.rows();
        assertTrue(dao.findByUsername("nobody").isEmpty());
    }
}
