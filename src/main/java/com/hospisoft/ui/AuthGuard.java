package com.hospisoft.ui;

import com.hospisoft.model.Rol;
import com.hospisoft.service.PageAccess;

/** Pure navigation rules, kept free of JavaFX so they can be unit-tested. */
public final class AuthGuard {

    public static final String LOGIN_PAGE = PageAccess.LOGIN_PAGE;
    public static final String HOME_PAGE = PageAccess.HOME_PAGE;

    private AuthGuard() {
    }

    /**
     * Page that must actually be shown when {@code requested} is asked for.
     *
     * @param rol role of the current session, or null when nobody is logged in
     * @return login without a session; home for login, unknown or forbidden pages; otherwise the page
     */
    public static String resolve(String requested, Rol rol) {
        if (rol == null) {
            return LOGIN_PAGE;
        }
        if (!PageAccess.isKnownPage(requested) || LOGIN_PAGE.equals(requested)) {
            return HOME_PAGE;
        }
        if (!PageAccess.isAllowed(requested, rol)) {
            return HOME_PAGE;
        }
        return requested;
    }

    /**
     * True only for a location that lives under the classpath web folder and names a known page.
     * Anything else (https://..., about:blank, a directory, an unknown file) is foreign: the bridge
     * must never be injected into it.
     */
    public static boolean isLocal(String location, String base) {
        if (location == null || base == null || base.isEmpty() || !location.startsWith(base)) {
            return false;
        }
        return PageAccess.isKnownPage(pageNameOf(location));
    }

    /** Last path segment of a location URL without query or fragment; empty when unknown. */
    public static String pageNameOf(String location) {
        if (location == null || location.isBlank()) {
            return "";
        }
        String path = location;
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
