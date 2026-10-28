package com.hospisoft.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.model.Rol;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageAccessTest {

    @Test
    void everyRoleHasAnEntry() {
        for (Rol rol : Rol.values()) {
            assertTrue(PageAccess.roles().contains(rol), rol.name());
            assertTrue(PageAccess.allowedPages(rol).contains("index.html"), rol.name());
        }
    }

    @Test
    void allowedPagesPerRole() {
        assertEquals(List.of("index.html", "medicos.html", "pacientes.html", "citas.html", "medicamentos.html"),
            PageAccess.allowedPages(Rol.ADMIN));
        assertEquals(List.of("index.html", "pacientes.html", "citas.html", "medicamentos.html"),
            PageAccess.allowedPages(Rol.MEDICO));
        assertEquals(List.of("index.html", "citas.html", "medicamentos.html"),
            PageAccess.allowedPages(Rol.PACIENTE));
        assertEquals(List.of(), PageAccess.allowedPages(null));
    }

    @Test
    void loginIsAlwaysAllowedAndOtherPagesNeedARole() {
        assertTrue(PageAccess.isAllowed("login.html", null));
        assertTrue(PageAccess.isAllowed("login.html", Rol.PACIENTE));
        assertFalse(PageAccess.isAllowed("index.html", null));
        assertFalse(PageAccess.isAllowed("medicos.html", Rol.MEDICO));
        assertFalse(PageAccess.isAllowed("pacientes.html", Rol.PACIENTE));
        assertTrue(PageAccess.isAllowed("medicos.html", Rol.ADMIN));
        assertFalse(PageAccess.isAllowed("nope.html", Rol.ADMIN));
    }

    @Test
    void knownPages() {
        assertTrue(PageAccess.isKnownPage("citas.html"));
        assertFalse(PageAccess.isKnownPage("../etc/passwd"));
        assertFalse(PageAccess.isKnownPage(null));
    }
}
