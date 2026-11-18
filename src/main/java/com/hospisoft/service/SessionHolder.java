package com.hospisoft.service;

import java.util.Optional;

public final class SessionHolder {

    private volatile SessionContext current;

    public Optional<SessionContext> current() {
        return Optional.ofNullable(current);
    }

    public void set(SessionContext session) {
        this.current = session;
    }

    public void clear() {
        this.current = null;
    }
}
