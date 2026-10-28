package com.hospisoft.ui;

import com.hospisoft.bridge.Bridge;
import com.hospisoft.model.Rol;
import com.hospisoft.service.PageAccess;
import com.hospisoft.service.SessionContext;
import com.hospisoft.service.SessionHolder;
import java.net.URL;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;

/**
 * Loads pages from the classpath ({@code web/}) and enforces the auth and role guard in Java.
 *
 * <p>Pages are plain {@code <a href="medicos.html">} links, so the engine itself resolves the
 * relative URL. {@link #enforceGuard(String)} runs on every location change: foreign locations
 * (anything outside the classpath web folder) and forbidden pages are redirected, and the caller
 * must not inject the bridge into them.
 */
public final class PageRouter implements Bridge.Navigator {

    private static final String RESOURCE_ROOT = "/web/";

    /** Classpath base every legitimate location starts with, e.g. {@code jar:file:/.../app.jar!/web/}. */
    static final String BASE = baseUrl();

    private final WebEngine engine;
    private final SessionHolder sessions;

    public PageRouter(WebEngine engine, SessionHolder sessions) {
        this.engine = engine;
        this.sessions = sessions;
    }

    @Override
    public void navigate(String page) {
        load(page);
    }

    @Override
    public boolean exists(String page) {
        return PageAccess.isKnownPage(page) && PageRouter.class.getResource(RESOURCE_ROOT + page) != null;
    }

    /** Guarded load: the page actually shown may differ from the one requested. */
    public void load(String page) {
        String resolved = AuthGuard.resolve(page, currentRol());
        Platform.runLater(() -> engine.load(urlOf(resolved)));
    }

    /**
     * Applies the guard to a location the engine is loading or has loaded.
     *
     * @return true when a redirect was issued (the caller must not inject the bridge)
     */
    public boolean enforceGuard(String location) {
        if (location == null || location.isBlank()) {
            return false; // fresh engine, nothing loaded yet
        }
        if (!AuthGuard.isLocal(location, BASE)) {
            System.out.println("[router] foreign location blocked: " + location);
            load(AuthGuard.LOGIN_PAGE);
            return true;
        }
        String page = AuthGuard.pageNameOf(location);
        String resolved = AuthGuard.resolve(page, currentRol());
        if (resolved.equals(page)) {
            return false;
        }
        System.out.println("[router] " + page + " -> " + resolved);
        Platform.runLater(() -> engine.load(urlOf(resolved)));
        return true;
    }

    private Rol currentRol() {
        return sessions.current().map(SessionContext::rol).orElse(null);
    }

    public static String urlOf(String page) {
        URL url = PageRouter.class.getResource(RESOURCE_ROOT + page);
        if (url == null) {
            throw new IllegalStateException("Missing web resource: " + page);
        }
        return url.toExternalForm();
    }

    private static String baseUrl() {
        String login = urlOf(PageAccess.LOGIN_PAGE);
        return login.substring(0, login.length() - PageAccess.LOGIN_PAGE.length());
    }
}
