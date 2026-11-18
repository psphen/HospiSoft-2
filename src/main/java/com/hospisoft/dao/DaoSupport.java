package com.hospisoft.dao;

import com.hospisoft.service.NotFoundException;
import com.hospisoft.service.Validation;
import com.hospisoft.service.ValidationException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

final class DaoSupport {

    static final String UNIQUE_VIOLATION = "23505";
    static final String FOREIGN_KEY_VIOLATION = "23503";

    private DaoSupport() {
    }

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

    static RuntimeException duplicateDocumento(SQLException ex) {
        return duplicateKey(ex, "documento");
    }

    static RuntimeException duplicateKey(SQLException ex, String field) {
        if (UNIQUE_VIOLATION.equals(ex.getSQLState())) {
            return new ValidationException(Map.of(field, Validation.duplicateMessage(field)));
        }
        return null;
    }

    static RuntimeException hasHistory(SQLException ex) {
        if (FOREIGN_KEY_VIOLATION.equals(ex.getSQLState())) {
            return new HasHistoryException("Row is referenced by other rows", ex);
        }
        return null;
    }

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

    static void requireAffected(int affected, String entity, long id) {
        if (affected == 0) {
            throw new NotFoundException(entity + " " + id + " not found");
        }
    }
}
