package com.hospisoft.dao;

import com.hospisoft.model.Usuario;
import java.util.Optional;

/**
 * Read access to usuario rows. The service layer depends on this interface, never on the JDBC DAO,
 * so it can be tested with an in-memory fake. Implementations throw
 * {@link com.hospisoft.db.DatabaseUnavailableException} when the database cannot be reached.
 */
@FunctionalInterface
public interface UsuarioRepository {

    Optional<Usuario> findByUsername(String username);
}
