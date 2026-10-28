package com.hospisoft.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.model.Rol;
import org.junit.jupiter.api.Test;

class AuthGuardTest {

    private static final String BASE = "jar:file:/C:/x/app.jar!/web/";

    @Test
    void withoutSessionEveryPageRedirectsToLogin() {
        assertEquals("login.html", AuthGuard.resolve("index.html", null));
        assertEquals("login.html", AuthGuard.resolve("citas.html", null));
        assertEquals("login.html", AuthGuard.resolve("login.html", null));
        assertEquals("login.html", AuthGuard.resolve("nope.html", null));
        assertEquals("login.html", AuthGuard.resolve(null, null));
    }

    @Test
    void withSessionLoginAndUnknownPagesRedirectToIndex() {
        assertEquals("index.html", AuthGuard.resolve("login.html", Rol.ADMIN));
        assertEquals("index.html", AuthGuard.resolve("nope.html", Rol.ADMIN));
        assertEquals("index.html", AuthGuard.resolve(null, Rol.ADMIN));
    }

    @Test
    void adminMayOpenEveryPage() {
        for (String page : new String[] {"index.html", "medicos.html", "pacientes.html", "citas.html", "medicamentos.html"}) {
            assertEquals(page, AuthGuard.resolve(page, Rol.ADMIN));
        }
    }

    @Test
    void medicoCannotOpenMedicos() {
        assertEquals("index.html", AuthGuard.resolve("medicos.html", Rol.MEDICO));
        assertEquals("pacientes.html", AuthGuard.resolve("pacientes.html", Rol.MEDICO));
        assertEquals("citas.html", AuthGuard.resolve("citas.html", Rol.MEDICO));
        assertEquals("medicamentos.html", AuthGuard.resolve("medicamentos.html", Rol.MEDICO));
        assertEquals("index.html", AuthGuard.resolve("index.html", Rol.MEDICO));
    }

    @Test
    void pacienteCannotOpenMedicosNorPacientes() {
        assertEquals("index.html", AuthGuard.resolve("medicos.html", Rol.PACIENTE));
        assertEquals("index.html", AuthGuard.resolve("pacientes.html", Rol.PACIENTE));
        assertEquals("citas.html", AuthGuard.resolve("citas.html", Rol.PACIENTE));
        assertEquals("medicamentos.html", AuthGuard.resolve("medicamentos.html", Rol.PACIENTE));
    }

    @Test
    void extractsPageNameFromClasspathAndJarUrls() {
        assertEquals("citas.html", AuthGuard.pageNameOf("file:/C:/x/target/classes/web/citas.html"));
        assertEquals("index.html", AuthGuard.pageNameOf("jar:file:/C:/x/app.jar!/web/index.html"));
        assertEquals("login.html", AuthGuard.pageNameOf("jar:file:/C:/x/app.jar!/web/login.html?x=1#top"));
        assertEquals("", AuthGuard.pageNameOf("https://x.test/"));
        assertEquals("", AuthGuard.pageNameOf(""));
        assertEquals("", AuthGuard.pageNameOf(null));
    }

    @Test
    void localPagesUnderTheBaseAreRecognized() {
        assertTrue(AuthGuard.isLocal(BASE + "login.html", BASE));
        assertTrue(AuthGuard.isLocal(BASE + "citas.html#top", BASE));
        assertTrue(AuthGuard.isLocal(BASE + "index.html?x=1", BASE));
    }

    @Test
    void foreignLocationsAreRejected() {
        assertFalse(AuthGuard.isLocal("https://host/", BASE));
        assertFalse(AuthGuard.isLocal("https://host/index.html", BASE));
        assertFalse(AuthGuard.isLocal("about:blank", BASE));
        assertFalse(AuthGuard.isLocal(BASE, BASE));                       // trailing slash, no page
        assertFalse(AuthGuard.isLocal(BASE + "other.html", BASE));        // unknown page under the base
        assertFalse(AuthGuard.isLocal(BASE + "css/style.css", BASE));
        assertFalse(AuthGuard.isLocal("jar:file:/C:/x/app.jar!/index.html", BASE));
        assertFalse(AuthGuard.isLocal(null, BASE));
        assertFalse(AuthGuard.isLocal(BASE + "index.html", null));
    }
}
