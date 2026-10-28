package com.hospisoft.service;

import com.hospisoft.dao.UsuarioRepository;
import com.hospisoft.model.Rol;
import com.hospisoft.model.Usuario;
import com.hospisoft.security.PasswordHasher;
import java.util.Optional;

/**
 * Login use case. Every credential failure (unknown user, wrong password, inactive account) raises
 * the same generic message so the caller cannot tell which one happened. Repeated failures for a
 * username are throttled by {@link LoginThrottle}.
 */
public final class AuthService {

    public static final String GENERIC_FAILURE = "Usuario o contraseña incorrectos";
    private static final String ADMIN_DISPLAY_NAME = "Administrador";

    private final UsuarioRepository usuarios;
    private final LoginThrottle throttle;

    public AuthService(UsuarioRepository usuarios) {
        this(usuarios, new LoginThrottle());
    }

    public AuthService(UsuarioRepository usuarios, LoginThrottle throttle) {
        this.usuarios = usuarios;
        this.throttle = throttle;
    }

    /**
     * @throws AuthenticationException                       on any credential problem or when throttled
     * @throws com.hospisoft.db.DatabaseUnavailableException when the repository cannot reach the DB
     */
    public SessionContext login(String username, char[] password) {
        if (username == null || username.isBlank() || password == null || password.length == 0) {
            throw new AuthenticationException(GENERIC_FAILURE);
        }
        String normalized = username.trim();
        throttle.check(normalized);
        Optional<Usuario> found = usuarios.findByUsername(normalized);
        if (found.isEmpty()) {
            // Run the KDF anyway so timing does not reveal whether the user exists.
            PasswordHasher.hash(password);
            return fail(normalized);
        }
        Usuario usuario = found.get();
        boolean valid = PasswordHasher.verify(password, usuario.salt(), usuario.passwordHash());
        if (!valid || !usuario.activo()) {
            return fail(normalized);
        }
        throttle.recordSuccess(normalized);
        return new SessionContext(
            usuario.id(),
            usuario.username(),
            usuario.rol(),
            usuario.idReferencia(),
            displayNameOf(usuario));
    }

    private SessionContext fail(String username) {
        throttle.recordFailure(username);
        throw new AuthenticationException(GENERIC_FAILURE);
    }

    private static String displayNameOf(Usuario usuario) {
        String name = usuario.displayName();
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return usuario.rol() == Rol.ADMIN ? ADMIN_DISPLAY_NAME : usuario.username();
    }
}
