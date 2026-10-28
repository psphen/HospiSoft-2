package com.hospisoft.service;

import java.util.Optional;

/** Single mutable slot holding the current session. Shared by the Bridge and the page router. */
public final class SessionHolder {

    private volatile SessionContext current;

    public Optional<SessionContext> current() {
        return Optional.ofNullable(current);
    }

    public boolean isLoggedIn() {
        return current != null;
    }

    public void set(SessionContext session) {
        this.current = session;
    }

    public void clear() {
        this.current = null;
    }
}
