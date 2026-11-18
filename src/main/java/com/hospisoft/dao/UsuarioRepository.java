package com.hospisoft.dao;

import com.hospisoft.model.Usuario;
import java.util.Optional;

@FunctionalInterface
public interface UsuarioRepository {

    Optional<Usuario> findByUsername(String username);
}
