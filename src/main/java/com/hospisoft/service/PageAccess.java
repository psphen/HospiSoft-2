package com.hospisoft.service;

import com.hospisoft.model.Rol;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PageAccess {

    public static final String LOGIN_PAGE = "login.html";
    public static final String HOME_PAGE = "index.html";

    public static final String AUDITORIA_PAGE = "auditoria.html";

    public static final List<String> PAGES = List.of(
        LOGIN_PAGE, HOME_PAGE, "medicos.html", "pacientes.html", "citas.html", "medicamentos.html", AUDITORIA_PAGE);

    private static final Map<Rol, List<String>> ROLE_PAGES = new EnumMap<>(Rol.class);

    static {
        ROLE_PAGES.put(Rol.ADMIN, List.of(HOME_PAGE, "medicos.html", "pacientes.html", "citas.html", "medicamentos.html",
            AUDITORIA_PAGE));
        ROLE_PAGES.put(Rol.MEDICO, List.of(HOME_PAGE, "pacientes.html", "citas.html", "medicamentos.html"));
        ROLE_PAGES.put(Rol.PACIENTE, List.of(HOME_PAGE, "citas.html", "medicamentos.html"));
    }

    private PageAccess() {
    }

    public static boolean isKnownPage(String page) {
        return page != null && PAGES.contains(page);
    }

    public static List<String> allowedPages(Rol rol) {
        if (rol == null) {
            return List.of();
        }
        return ROLE_PAGES.getOrDefault(rol, List.of());
    }

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
