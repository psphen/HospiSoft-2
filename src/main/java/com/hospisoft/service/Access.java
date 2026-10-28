package com.hospisoft.service;

import com.hospisoft.model.Rol;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;

/** Permission checks shared by the services: every operation needs a session with an allowed role. */
final class Access {

    static final String NO_SESSION = "No session";
    static final String ROLE_NOT_ALLOWED = "Role not allowed: ";

    private final Supplier<Optional<SessionContext>> session;

    Access(Supplier<Optional<SessionContext>> session) {
        this.session = session;
    }

    /** @throws PermissionException when nobody is logged in or the role is not one of {@code allowed} */
    SessionContext require(Rol... allowed) {
        SessionContext current = session.get().orElseThrow(() -> new PermissionException(NO_SESSION));
        if (Arrays.stream(allowed).noneMatch(rol -> rol == current.rol())) {
            throw new PermissionException(ROLE_NOT_ALLOWED + current.rol());
        }
        return current;
    }
}
