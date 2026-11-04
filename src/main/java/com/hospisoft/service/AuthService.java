package com.hospisoft.service;

import com.hospisoft.dao.UsuarioRepository;
import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.ModuloAuditoria;
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
    /** Audit text of a successful login. */
    public static final String LOGIN_DETALLE = "Ingreso al sistema";
    /** Audit text of a login attempt with a username that does not exist (the input is never stored). */
    public static final String UNKNOWN_USER_DETALLE = "Usuario inexistente";
    private static final String ADMIN_DISPLAY_NAME = "Administrador";

    private final UsuarioRepository usuarios;
    private final LoginThrottle throttle;
    private final AuditLog audit;

    /** Without an audit trail (tests and tools that only need authentication). */
    public AuthService(UsuarioRepository usuarios) {
        this(usuarios, new LoginThrottle(), AuditLog.NONE);
    }

    public AuthService(UsuarioRepository usuarios, AuditLog audit) {
        this(usuarios, new LoginThrottle(), audit);
    }

    /**
     * @param audit records LOGIN on success and LOGIN_FALLIDO on bad credentials or lockout: the
     *              username only when it names an existing account (unknown input is never stored, it
     *              may be a mistyped password), the reason shown to the user, never the password
     */
    public AuthService(UsuarioRepository usuarios, LoginThrottle throttle, AuditLog audit) {
        this.usuarios = usuarios;
        this.throttle = throttle;
        this.audit = audit;
    }

    /**
     * @throws AuthenticationException                       on any credential problem or when throttled
     * @throws com.hospisoft.db.DatabaseUnavailableException when the repository cannot reach the DB
     */
    public SessionContext login(String username, char[] password) {
        if (username == null || username.isBlank()) {
            throw new AuthenticationException(GENERIC_FAILURE);
        }
        String normalized = username.trim();
        if (password == null || password.length == 0) {
            audit.recordFailedLogin(knownUsername(normalized), GENERIC_FAILURE);
            throw new AuthenticationException(GENERIC_FAILURE);
        }
        try {
            throttle.check(normalized);
        } catch (AuthenticationException locked) {
            // the throttle is keyed by the raw input: store it only when it names a real account
            audit.recordFailedLogin(knownUsername(normalized), locked.getMessage());
            throw locked;
        }
        Optional<Usuario> found = usuarios.findByUsername(normalized);
        if (found.isEmpty()) {
            // Run the KDF anyway so timing does not reveal whether the user exists.
            PasswordHasher.hash(password);
            throttle.recordFailure(normalized);
            // never echo the input: it may be a password typed into the username box
            audit.recordFailedLogin(null, UNKNOWN_USER_DETALLE);
            throw new AuthenticationException(GENERIC_FAILURE);
        }
        Usuario usuario = found.get();
        boolean valid = PasswordHasher.verify(password, usuario.salt(), usuario.passwordHash());
        if (!valid || !usuario.activo()) {
            return fail(normalized, usuario.username());
        }
        throttle.recordSuccess(normalized);
        SessionContext session = new SessionContext(
            usuario.id(),
            usuario.username(),
            usuario.rol(),
            usuario.idReferencia(),
            displayNameOf(usuario));
        audit.recordAs(session, AccionAuditoria.LOGIN, ModuloAuditoria.SESION, null, LOGIN_DETALLE);
        return session;
    }

    /** Wrong password or inactive account of an existing user: the stored username is audited. */
    private SessionContext fail(String attempted, String storedUsername) {
        throttle.recordFailure(attempted);
        audit.recordFailedLogin(storedUsername, GENERIC_FAILURE);
        throw new AuthenticationException(GENERIC_FAILURE);
    }

    /**
     * The stored username when {@code attempted} names an existing account, otherwise null, so the
     * audit log never keeps free text a user typed (it may be a password). A failing lookup also
     * yields null: auditing must not change the outcome of the login.
     */
    private String knownUsername(String attempted) {
        try {
            return usuarios.findByUsername(attempted).map(Usuario::username).orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String displayNameOf(Usuario usuario) {
        String name = usuario.displayName();
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return usuario.rol() == Rol.ADMIN ? ADMIN_DISPLAY_NAME : usuario.username();
    }
}
