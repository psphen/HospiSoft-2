package com.hospisoft.ui;

import com.hospisoft.model.Rol;
import com.hospisoft.service.PageAccess;

public final class AuthGuard {

    public static final String LOGIN_PAGE = PageAccess.LOGIN_PAGE;
    public static final String HOME_PAGE = PageAccess.HOME_PAGE;

    private AuthGuard() {
    }

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

    public static boolean isLocal(String location, String base) {
        if (location == null || base == null || base.isEmpty() || !location.startsWith(base)) {
            return false;
        }
        return PageAccess.isKnownPage(pageNameOf(location));
    }

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
