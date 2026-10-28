package com.hospisoft.service;

import com.hospisoft.model.Rol;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for which page each role may open. Used by the Java navigation guard,
 * by Bridge.navigate and (through Bridge.getAllowedPages) by the sidebar link hiding in app.js.
 */
public final class PageAccess {

    public static final String LOGIN_PAGE = "login.html";
    public static final String HOME_PAGE = "index.html";

    /** Every page shipped in the classpath web/ folder, in sidebar order. */
    public static final List<String> PAGES = List.of(
        LOGIN_PAGE, HOME_PAGE, "medicos.html", "pacientes.html", "citas.html", "medicamentos.html");

    private static final Map<Rol, List<String>> ROLE_PAGES = new EnumMap<>(Rol.class);

    static {
        ROLE_PAGES.put(Rol.ADMIN, List.of(HOME_PAGE, "medicos.html", "pacientes.html", "citas.html", "medicamentos.html"));
        ROLE_PAGES.put(Rol.MEDICO, List.of(HOME_PAGE, "pacientes.html", "citas.html", "medicamentos.html"));
        ROLE_PAGES.put(Rol.PACIENTE, List.of(HOME_PAGE, "citas.html", "medicamentos.html"));
    }

    private PageAccess() {
    }

    public static boolean isKnownPage(String page) {
        return page != null && PAGES.contains(page);
    }

    /** Pages (login excluded) the role may open; empty list for a null role. */
    public static List<String> allowedPages(Rol rol) {
        if (rol == null) {
            return List.of();
        }
        return ROLE_PAGES.getOrDefault(rol, List.of());
    }

    /** Login is always reachable; every other page needs a role that lists it. */
    public static boolean isAllowed(String page, Rol rol) {
        if (LOGIN_PAGE.equals(page)) {
            return true;
        }
        return rol != null && allowedPages(rol).contains(page);
    }

    static Set<Rol> roles() {
        return ROLE_PAGES.keySet();
    }
}
