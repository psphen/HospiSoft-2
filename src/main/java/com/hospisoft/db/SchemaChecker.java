package com.hospisoft.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SchemaChecker {
    public static final List<String> REQUIRED_TABLES = List.of(
        "usuario", "medico", "paciente", "cita", "medicamento", "historia_clinica", "receta", "auditoria");

    private static final String SQL =
        "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'";

    private final ConnectionPool pool;

    public SchemaChecker(ConnectionPool pool) {
        this.pool = pool;
    }

    public static List<String> missingFrom(Set<String> foundTables) {
        Set<String> normalized = new HashSet<>();
        for (String name : foundTables) {
            normalized.add(name.toLowerCase());
        }
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_TABLES) {
            if (!normalized.contains(required)) {
                missing.add(required);
            }
        }
        return missing;
    }

    public List<String> findMissing() {
        return pool.withConnection(connection -> {
            Set<String> found = new HashSet<>();
            try (PreparedStatement ps = connection.prepareStatement(SQL)) {
                ps.setString(1, "public");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        found.add(rs.getString(1));
                    }
                }
            }
            return missingFrom(found);
        });
    }
}
