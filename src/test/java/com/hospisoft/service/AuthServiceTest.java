package com.hospisoft.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hospisoft.dao.UsuarioRepository;
import com.hospisoft.model.Rol;
import com.hospisoft.model.Usuario;
import com.hospisoft.security.PasswordHasher;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthServiceTest {

    /** In-memory fake: no database involved. */
    static final class FakeUsuarioRepository implements UsuarioRepository {
        private final Map<String, Usuario> byUsername = new HashMap<>();
        /** Mirrors the DAO rule: the linked medico/paciente row must be active too. */
        private final Map<String, Boolean> linkedActivo = new HashMap<>();

        void add(Usuario usuario) {
            byUsername.put(usuario.username(), usuario);
        }

        void setLinkedActivo(String username, boolean activo) {
            linkedActivo.put(username, activo);
        }

        @Override
        public Optional<Usuario> findByUsername(String username) {
            Usuario u = byUsername.get(username);
            if (u == null) {
                return Optional.empty();
            }
            boolean effective = u.activo() && linkedActivo.getOrDefault(username, true);
            return Optional.of(new Usuario(u.id(), u.username(), u.passwordHash(), u.salt(), u.rol(), effective,
                u.idReferencia(), u.displayName()));
        }
    }

    // PBKDF2 with the production iteration count is slow: hash the fixtures once per class.
    private static Usuario admin;
    private static Usuario medico;
    private static Usuario inactivo;

    private FakeUsuarioRepository repository;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private LoginThrottle throttle;
    private AuthService service;

    private static Usuario user(long id, String username, String password, Rol rol, boolean activo,
                                Long idReferencia, String displayName) {
        PasswordHasher.Hashed hashed = PasswordHasher.hash(password.toCharArray());
        return new Usuario(id, username, hashed.hashBase64(), hashed.saltBase64(), rol, activo, idReferencia, displayName);
    }

    @BeforeAll
    static void hashFixtures() {
        admin = user(1, "admin", "Admin123*", Rol.ADMIN, true, null, null);
        medico = user(2, "dr.garcia", "Medico123*", Rol.MEDICO, true, 10L, "Carlos Garcia");
        inactivo = user(3, "inactivo", "Secret123*", Rol.PACIENTE, false, 20L, "Ana Torres");
    }

    @BeforeEach
    void setUp() {
        repository = new FakeUsuarioRepository();
        repository.add(admin);
        repository.add(medico);
        repository.add(inactivo);
        throttle = new LoginThrottle(now::get);
        service = new AuthService(repository, throttle);
    }

    @Test
    void successfulLoginReturnsSessionWithRoleAndReference() {
        SessionContext session = service.login("dr.garcia", "Medico123*".toCharArray());
        assertEquals(2L, session.userId());
        assertEquals("dr.garcia", session.username());
        assertEquals(Rol.MEDICO, session.rol());
        assertEquals(10L, session.idReferencia());
        assertEquals("Carlos Garcia", session.displayName());
    }

    @Test
    void adminWithoutNameGetsDefaultDisplayName() {
        SessionContext session = service.login("admin", "Admin123*".toCharArray());
        assertEquals(Rol.ADMIN, session.rol());
        assertEquals("Administrador", session.displayName());
    }

    @Test
    void unknownUserAndWrongPasswordGiveTheSameMessage() {
        AuthenticationException unknown = assertThrows(AuthenticationException.class,
            () -> service.login("nobody", "whatever".toCharArray()));
        AuthenticationException wrongPassword = assertThrows(AuthenticationException.class,
            () -> service.login("admin", "wrong".toCharArray()));
        assertEquals(AuthService.GENERIC_FAILURE, unknown.getMessage());
        assertEquals(AuthService.GENERIC_FAILURE, wrongPassword.getMessage());
        assertEquals("Usuario o contraseña incorrectos", unknown.getMessage());
    }

    @Test
    void inactiveUserIsRejectedWithTheSameMessage() {
        AuthenticationException inactive = assertThrows(AuthenticationException.class,
            () -> service.login("inactivo", "Secret123*".toCharArray()));
        assertEquals(AuthService.GENERIC_FAILURE, inactive.getMessage());
    }

    @Test
    void deactivatedLinkedMedicoCannotLogIn() {
        repository.setLinkedActivo("dr.garcia", false);
        AuthenticationException ex = assertThrows(AuthenticationException.class,
            () -> service.login("dr.garcia", "Medico123*".toCharArray()));
        assertEquals(AuthService.GENERIC_FAILURE, ex.getMessage());
        repository.setLinkedActivo("dr.garcia", true);
        assertEquals("dr.garcia", service.login("dr.garcia", "Medico123*".toCharArray()).username());
    }

    @Test
    void blankCredentialsAreRejected() {
        assertThrows(AuthenticationException.class, () -> service.login("", "x".toCharArray()));
        assertThrows(AuthenticationException.class, () -> service.login(null, "x".toCharArray()));
        assertThrows(AuthenticationException.class, () -> service.login("admin", null));
    }

    @Test
    void usernameIsTrimmed() {
        SessionContext session = service.login("  admin  ", "Admin123*".toCharArray());
        assertEquals("admin", session.username());
    }

    @Test
    void fiveFailuresLockTheUsernameForThirtySeconds() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            assertThrows(AuthenticationException.class, () -> service.login("admin", "wrong".toCharArray()));
        }
        AuthenticationException locked = assertThrows(AuthenticationException.class,
            () -> service.login("admin", "Admin123*".toCharArray()));
        assertEquals(LoginThrottle.LOCKED_MESSAGE, locked.getMessage());

        now.addAndGet(LoginThrottle.LOCK_MILLIS);
        SessionContext session = service.login("admin", "Admin123*".toCharArray());
        assertEquals("admin", session.username());
    }

    @Test
    void failuresForUnknownUsersAreThrottledToo() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            assertThrows(AuthenticationException.class, () -> service.login("ghost", "x".toCharArray()));
        }
        AuthenticationException locked = assertThrows(AuthenticationException.class,
            () -> service.login("ghost", "x".toCharArray()));
        assertEquals(LoginThrottle.LOCKED_MESSAGE, locked.getMessage());
    }

    @Test
    void successResetsTheFailureCounter() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            assertThrows(AuthenticationException.class, () -> service.login("admin", "wrong".toCharArray()));
        }
        service.login("admin", "Admin123*".toCharArray());
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            assertThrows(AuthenticationException.class, () -> service.login("admin", "wrong".toCharArray()));
        }
        service.login("admin", "Admin123*".toCharArray()); // still not locked
    }
}
