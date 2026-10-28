package com.hospisoft.dao;

import com.hospisoft.service.NotFoundException;
import com.hospisoft.service.Validation;
import com.hospisoft.service.ValidationException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

/**
 * Helpers shared by the JDBC DAOs: parameter binding and translation of constraint violations
 * into the typed exceptions the services and the bridge understand. Typed runtime exceptions pass
 * through {@code ConnectionPool.withConnection} unchanged (it only classifies SQLExceptions).
 */
final class DaoSupport {

    static final String UNIQUE_VIOLATION = "23505";
    static final String FOREIGN_KEY_VIOLATION = "23503";

    private DaoSupport() {
    }

    /** Binds filter parameters (String, Boolean, Long, LocalDate) starting at index 1; returns the next free index. */
    static int bind(PreparedStatement ps, List<Object> params) throws SQLException {
        int index = 1;
        for (Object value : params) {
            ps.setObject(index++, value);
        }
        return index;
    }

    static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    /** A UNIQUE violation on documento becomes a field validation error; anything else propagates. */
    static RuntimeException duplicateDocumento(SQLException ex) {
        return duplicateKey(ex, "documento");
    }

    /** A UNIQUE violation becomes a validation error on {@code field}; anything else propagates (null). */
    static RuntimeException duplicateKey(SQLException ex, String field) {
        if (UNIQUE_VIOLATION.equals(ex.getSQLState())) {
            return new ValidationException(Map.of(field, Validation.duplicateMessage(field)));
        }
        return null;
    }

    /** A FOREIGN KEY violation on delete means the row has history; anything else propagates. */
    static RuntimeException hasHistory(SQLException ex) {
        if (FOREIGN_KEY_VIOLATION.equals(ex.getSQLState())) {
            return new HasHistoryException("Row is referenced by other rows", ex);
        }
        return null;
    }

    /**
     * A FOREIGN KEY violation on insert/update becomes a validation error on the field whose column
     * name appears in the driver message (e.g. {@code cita_id_paciente_fkey}); null when not a FK error.
     *
     * @param columnToField column name -> form field name
     */
    static RuntimeException foreignKeyViolation(SQLException ex, Map<String, String> columnToField) {
        if (!FOREIGN_KEY_VIOLATION.equals(ex.getSQLState())) {
            return null;
        }
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        for (Map.Entry<String, String> entry : columnToField.entrySet()) {
            if (message.contains(entry.getKey())) {
                return new ValidationException(Map.of(entry.getValue(), Validation.NOT_EXISTS));
            }
        }
        Map<String, String> all = new java.util.LinkedHashMap<>();
        for (String field : columnToField.values()) {
            all.put(field, Validation.NOT_EXISTS);
        }
        return new ValidationException(all);
    }

    /** Throws when an UPDATE/DELETE touched no row. */
    static void requireAffected(int affected, String entity, long id) {
        if (affected == 0) {
            throw new NotFoundException(entity + " " + id + " not found");
        }
    }
}
