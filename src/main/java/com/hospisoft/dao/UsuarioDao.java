package com.hospisoft.dao;

import com.hospisoft.db.ConnectionPool;
import com.hospisoft.model.Rol;
import com.hospisoft.model.Usuario;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * JDBC implementation of {@link UsuarioRepository}. PreparedStatement only, never string concatenation.
 *
 * <p>The effective {@code activo} flag also requires the linked medico/paciente row to be active:
 * a deactivated professional or patient cannot log in even if the account itself is still active.
 */
public final class UsuarioDao implements UsuarioRepository {

    static final String FIND_BY_USERNAME = """
        SELECT u.id, u.username, u.password_hash, u.salt, u.rol,
               (u.activo AND COALESCE(m.activo, p.activo, TRUE)) AS activo,
               u.id_referencia,
               COALESCE(m.nombres || ' ' || m.apellidos, p.nombres || ' ' || p.apellidos) AS display_name
        FROM usuario u
        LEFT JOIN medico m ON u.rol = 'MEDICO' AND m.id = u.id_referencia
        LEFT JOIN paciente p ON u.rol = 'PACIENTE' AND p.id = u.id_referencia
        WHERE u.username = ?
        """;

    private final ConnectionPool pool;

    public UsuarioDao(ConnectionPool pool) {
        this.pool = pool;
    }

    @Override
    public Optional<Usuario> findByUsername(String username) {
        return pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(FIND_BY_USERNAME)) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(map(rs));
                }
            }
        });
    }

    private static Usuario map(ResultSet rs) throws SQLException {
        long idReferencia = rs.getLong("id_referencia");
        Long reference = rs.wasNull() ? null : idReferencia;
        return new Usuario(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("salt"),
            Rol.valueOf(rs.getString("rol")),
            rs.getBoolean("activo"),
            reference,
            rs.getString("display_name"));
    }
}
