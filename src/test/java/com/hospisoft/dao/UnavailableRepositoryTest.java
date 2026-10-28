package com.hospisoft.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hospisoft.db.DatabaseUnavailableException;
import org.junit.jupiter.api.Test;

class UnavailableRepositoryTest {

    @Test
    void everyRepositoryMethodThrowsDatabaseUnavailable() {
        MedicoRepository medicos = UnavailableRepository.create(MedicoRepository.class);
        PacienteRepository pacientes = UnavailableRepository.create(PacienteRepository.class);
        UsuarioRepository usuarios = UnavailableRepository.create(UsuarioRepository.class);
        assertThrows(DatabaseUnavailableException.class, () -> medicos.findPage(MedicoFilter.NONE, 1, 10));
        assertThrows(DatabaseUnavailableException.class, () -> medicos.findEspecialidades());
        assertThrows(DatabaseUnavailableException.class, () -> pacientes.findByIdAttendedBy(1, 2));
        assertThrows(DatabaseUnavailableException.class, () -> usuarios.findByUsername("admin"));
    }

    @Test
    void objectMethodsStillWork() {
        MedicoRepository medicos = UnavailableRepository.create(MedicoRepository.class);
        assertEquals("UnavailableMedicoRepository", medicos.toString());
        assertNotNull(Integer.valueOf(medicos.hashCode()));
        assertEquals(medicos, medicos);
    }
}
