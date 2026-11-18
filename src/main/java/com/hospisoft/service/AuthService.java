package com.hospisoft.service;

import com.hospisoft.dao.UsuarioRepository;
import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.model.Rol;
import com.hospisoft.model.Usuario;
import com.hospisoft.security.PasswordHasher;
import java.util.Optional;

public final class AuthService {

    public static final String GENERIC_FAILURE = "Usuario o contraseña incorrectos";
    public static final String LOGIN_DETALLE = "Ingreso al sistema";
    public static final String UNKNOWN_USER_DETALLE = "Usuario inexistente";
    private static final String ADMIN_DISPLAY_NAME = "Administrador";

    private final UsuarioRepository usuarios;
    private final LoginThrottle throttle;
    private final AuditLog audit;

    public AuthService(UsuarioRepository usuarios) {
        this(usuarios, new LoginThrottle(), AuditLog.NONE);
    }

    public AuthService(UsuarioRepository usuarios, AuditLog audit) {
        this(usuarios, new LoginThrottle(), audit);
    }

    public AuthService(UsuarioRepository usuarios, LoginThrottle throttle, AuditLog audit) {
        this.usuarios = usuarios;
        this.throttle = throttle;
        this.audit = audit;
    }

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
            audit.recordFailedLogin(knownUsername(normalized), locked.getMessage());
            throw locked;
        }
        Optional<Usuario> found = usuarios.findByUsername(normalized);
        if (found.isEmpty()) {
            PasswordHasher.hash(password);
            throttle.recordFailure(normalized);
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

    private SessionContext fail(String attempted, String storedUsername) {
        throttle.recordFailure(attempted);
        audit.recordFailedLogin(storedUsername, GENERIC_FAILURE);
        throw new AuthenticationException(GENERIC_FAILURE);
    }

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
