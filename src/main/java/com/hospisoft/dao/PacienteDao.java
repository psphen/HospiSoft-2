package com.hospisoft.dao;

import com.hospisoft.db.ConnectionPool;
import com.hospisoft.model.Paciente;
import com.hospisoft.model.Page;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation of {@link PacienteRepository}. PreparedStatement only: the WHERE clause is
 * assembled from constant fragments and every user value travels as a parameter.
 *
 * <p>MEDICO scope ("attended"): at least one cita with the medico whose estado is not CANCELADA.
 * Dates are bound and read as {@link LocalDate} (pgjdbc maps DATE natively).
 * Constraint violations are translated like in {@link MedicoDao}.
 */
public final class PacienteDao implements PacienteRepository {

    static final String COLUMNS =
        "p.id, p.documento, p.nombres, p.apellidos, p.fecha_nacimiento, p.sexo, p.telefono, p.correo, p.direccion, p.activo";

    static final String SELECT = "SELECT " + COLUMNS + " FROM paciente p";

    static final String COUNT = "SELECT count(*) FROM paciente p";

    static final String INSERT = """
        INSERT INTO paciente (documento, nombres, apellidos, fecha_nacimiento, sexo, telefono, correo, direccion, activo)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """;

    static final String UPDATE = """
        UPDATE paciente
        SET documento = ?, nombres = ?, apellidos = ?, fecha_nacimiento = ?, sexo = ?, telefono = ?, correo = ?,
            direccion = ?, activo = ?
        WHERE id = ?
        """;

    static final String SET_ACTIVO = "UPDATE paciente SET activo = ? WHERE id = ?";

    static final String DELETE = "DELETE FROM paciente WHERE id = ?";

    static final String HAS_HISTORY = """
        SELECT EXISTS (SELECT 1 FROM cita WHERE id_paciente = ?)
            OR EXISTS (SELECT 1 FROM historia_clinica WHERE id_paciente = ?)
            OR EXISTS (SELECT 1 FROM usuario WHERE rol = 'PACIENTE' AND id_referencia = ?)
        """;

    /** Scope rule: a non-cancelled cita between the patient (p) and the medico parameter. */
    static final String ATTENDED_BY =
        "EXISTS (SELECT 1 FROM cita c WHERE c.id_paciente = p.id AND c.id_medico = ? AND c.estado <> 'CANCELADA')";

    static final String TEXT_CLAUSE =
        "(p.documento ILIKE ? ESCAPE '\\' OR p.nombres ILIKE ? ESCAPE '\\' OR p.apellidos ILIKE ? ESCAPE '\\'"
            + " OR (p.nombres || ' ' || p.apellidos) ILIKE ? ESCAPE '\\')";

    static final String ORDER = " ORDER BY p.apellidos, p.nombres, p.id";

    static final String PAGING = " LIMIT ? OFFSET ?";

    private static final String ENTITY = "Paciente";

    private final ConnectionPool pool;

    public PacienteDao(ConnectionPool pool) {
        this.pool = pool;
    }

    @Override
    public Page<Paciente> findPage(PacienteFilter filter, int page, int pageSize) {
        return findPage(null, filter, page, pageSize);
    }

    @Override
    public Page<Paciente> findPageAttendedBy(long idMedico, PacienteFilter filter, int page, int pageSize) {
        return findPage(idMedico, filter, page, pageSize);
    }

    private Page<Paciente> findPage(Long idMedico, PacienteFilter filter, int page, int pageSize) {
        PacienteFilter safe = filter == null ? PacienteFilter.NONE : filter;
        long offset = Filters.offset(page, pageSize);
        return pool.withConnection(connection -> {
            List<String> clauses = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            if (idMedico != null) {
                clauses.add(ATTENDED_BY);
                params.add(idMedico);
            }
            appendFilters(safe, clauses, params);
            String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);

            long total = count(connection, COUNT + where, params);
            List<Paciente> items = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(SELECT + where + ORDER + PAGING)) {
                int index = DaoSupport.bind(ps, params);
                ps.setLong(index++, pageSize);
                ps.setLong(index, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(map(rs));
                    }
                }
            }
            return new Page<>(items, total, page, pageSize);
        });
    }

    private static void appendFilters(PacienteFilter filter, List<String> clauses, List<Object> params) {
        if (filter.sexo() != null) {
            clauses.add("p.sexo = ?");
            params.add(filter.sexo());
        }
        if (filter.activo() != null) {
            clauses.add("p.activo = ?");
            params.add(filter.activo());
        }
        if (filter.texto() != null) {
            clauses.add(TEXT_CLAUSE);
            String pattern = Filters.likePattern(filter.texto());
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
    }

    @Override
    public Optional<Paciente> findByIdAttendedBy(long id, long idMedico) {
        return pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(SELECT + " WHERE p.id = ? AND " + ATTENDED_BY)) {
                ps.setLong(1, id);
                ps.setLong(2, idMedico);
                return queryOne(ps);
            }
        });
    }

    @Override
    public Optional<Paciente> findById(long id) {
        return pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(SELECT + " WHERE p.id = ?")) {
                ps.setLong(1, id);
                return queryOne(ps);
            }
        });
    }

    @Override
    public Optional<Paciente> findByDocumento(String documento) {
        return pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(SELECT + " WHERE p.documento = ?")) {
                ps.setString(1, documento);
                return queryOne(ps);
            }
        });
    }

    @Override
    public long insert(Paciente paciente) {
        return pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
                bindEntity(ps, paciente);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("INSERT did not return an id");
                    }
                    return rs.getLong(1);
                }
            } catch (SQLException ex) {
                throw rethrow(ex, DaoSupport.duplicateDocumento(ex));
            }
        });
    }

    @Override
    public void update(Paciente paciente) {
        pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(UPDATE)) {
                int index = bindEntity(ps, paciente);
                ps.setLong(index, paciente.id());
                DaoSupport.requireAffected(ps.executeUpdate(), ENTITY, paciente.id());
                return null;
            } catch (SQLException ex) {
                throw rethrow(ex, DaoSupport.duplicateDocumento(ex));
            }
        });
    }

    @Override
    public void setActivo(long id, boolean activo) {
        pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(SET_ACTIVO)) {
                ps.setBoolean(1, activo);
                ps.setLong(2, id);
                DaoSupport.requireAffected(ps.executeUpdate(), ENTITY, id);
                return null;
            }
        });
    }

    @Override
    public void delete(long id) {
        pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(DELETE)) {
                ps.setLong(1, id);
                DaoSupport.requireAffected(ps.executeUpdate(), ENTITY, id);
                return null;
            } catch (SQLException ex) {
                throw rethrow(ex, DaoSupport.hasHistory(ex));
            }
        });
    }

    @Override
    public boolean hasAssociatedHistory(long id) {
        return pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(HAS_HISTORY)) {
                ps.setLong(1, id);
                ps.setLong(2, id);
                ps.setLong(3, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        });
    }

    @Override
    public List<Paciente> findActiveForSelect() {
        return pool.withConnection(connection -> {
            List<Paciente> items = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(SELECT + " WHERE p.activo = ?" + ORDER)) {
                ps.setBoolean(1, true);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(map(rs));
                    }
                }
            }
            return items;
        });
    }

    // ------------------------------------------------------------ helpers

    private static SQLException rethrow(SQLException ex, RuntimeException typed) throws SQLException {
        if (typed != null) {
            throw typed;
        }
        throw ex;
    }

    private static long count(Connection connection, String sql, List<Object> params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            DaoSupport.bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** Binds the nine entity columns starting at index 1; returns the next free index. */
    private static int bindEntity(PreparedStatement ps, Paciente paciente) throws SQLException {
        ps.setString(1, paciente.documento());
        ps.setString(2, paciente.nombres());
        ps.setString(3, paciente.apellidos());
        ps.setObject(4, paciente.fechaNacimiento());
        ps.setString(5, paciente.sexo());
        DaoSupport.setNullable(ps, 6, paciente.telefono());
        DaoSupport.setNullable(ps, 7, paciente.correo());
        DaoSupport.setNullable(ps, 8, paciente.direccion());
        ps.setBoolean(9, paciente.activo());
        return 10;
    }

    private static Optional<Paciente> queryOne(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
        }
    }

    private static Paciente map(ResultSet rs) throws SQLException {
        return new Paciente(
            rs.getLong("id"),
            rs.getString("documento"),
            rs.getString("nombres"),
            rs.getString("apellidos"),
            rs.getObject("fecha_nacimiento", LocalDate.class),
            rs.getString("sexo"),
            rs.getString("telefono"),
            rs.getString("correo"),
            rs.getString("direccion"),
            rs.getBoolean("activo"));
    }
}
