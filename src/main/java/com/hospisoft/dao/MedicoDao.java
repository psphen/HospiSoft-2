package com.hospisoft.dao;

import com.hospisoft.db.ConnectionPool;
import com.hospisoft.model.Medico;
import com.hospisoft.model.Page;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation of {@link MedicoRepository}. PreparedStatement only: the WHERE clause is
 * assembled from constant fragments and every user value travels as a parameter.
 *
 * <p>Constraint violations are translated: UNIQUE on documento (23505) ->
 * {@code ValidationException}; FOREIGN KEY on delete (23503) -> {@link HasHistoryException};
 * zero affected rows -> {@code NotFoundException}.
 */
public final class MedicoDao implements MedicoRepository {

    static final String COLUMNS =
        "id, documento, nombres, apellidos, especialidad, telefono, correo, activo";

    static final String SELECT = "SELECT " + COLUMNS + " FROM medico";

    static final String COUNT = "SELECT count(*) FROM medico";

    static final String INSERT = """
        INSERT INTO medico (documento, nombres, apellidos, especialidad, telefono, correo, activo)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """;

    static final String UPDATE = """
        UPDATE medico
        SET documento = ?, nombres = ?, apellidos = ?, especialidad = ?, telefono = ?, correo = ?, activo = ?
        WHERE id = ?
        """;

    static final String SET_ACTIVO = "UPDATE medico SET activo = ? WHERE id = ?";

    static final String DELETE = "DELETE FROM medico WHERE id = ?";

    static final String HAS_HISTORY = """
        SELECT EXISTS (SELECT 1 FROM cita WHERE id_medico = ?)
            OR EXISTS (SELECT 1 FROM historia_clinica WHERE id_medico = ?)
            OR EXISTS (SELECT 1 FROM usuario WHERE rol = 'MEDICO' AND id_referencia = ?)
        """;

    static final String ESPECIALIDADES =
        "SELECT DISTINCT especialidad FROM medico WHERE especialidad IS NOT NULL ORDER BY especialidad";

    static final String TEXT_CLAUSE =
        "(documento ILIKE ? ESCAPE '\\' OR nombres ILIKE ? ESCAPE '\\' OR apellidos ILIKE ? ESCAPE '\\'"
            + " OR (nombres || ' ' || apellidos) ILIKE ? ESCAPE '\\')";

    static final String ORDER = " ORDER BY apellidos, nombres, id";

    static final String PAGING = " LIMIT ? OFFSET ?";

    private static final String ENTITY = "Medico";

    private final ConnectionPool pool;

    public MedicoDao(ConnectionPool pool) {
        this.pool = pool;
    }

    @Override
    public Page<Medico> findPage(MedicoFilter filter, int page, int pageSize) {
        MedicoFilter safe = filter == null ? MedicoFilter.NONE : filter;
        long offset = Filters.offset(page, pageSize);
        return pool.withConnection(connection -> {
            List<String> clauses = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            appendFilters(safe, clauses, params);
            String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);

            long total = count(connection, COUNT + where, params);
            List<Medico> items = new ArrayList<>();
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

    private static void appendFilters(MedicoFilter filter, List<String> clauses, List<Object> params) {
        if (filter.especialidad() != null) {
            clauses.add("especialidad = ?");
            params.add(filter.especialidad());
        }
        if (filter.activo() != null) {
            clauses.add("activo = ?");
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
    public Optional<Medico> findById(long id) {
        return pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(SELECT + " WHERE id = ?")) {
                ps.setLong(1, id);
                return queryOne(ps);
            }
        });
    }

    @Override
    public Optional<Medico> findByDocumento(String documento) {
        return pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(SELECT + " WHERE documento = ?")) {
                ps.setString(1, documento);
                return queryOne(ps);
            }
        });
    }

    @Override
    public long insert(Medico medico) {
        return pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
                bindEntity(ps, medico);
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
    public void update(Medico medico) {
        pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(UPDATE)) {
                int index = bindEntity(ps, medico);
                ps.setLong(index, medico.id());
                DaoSupport.requireAffected(ps.executeUpdate(), ENTITY, medico.id());
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
    public List<String> findEspecialidades() {
        return pool.withConnection(connection -> {
            List<String> values = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(ESPECIALIDADES);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
            }
            return values;
        });
    }

    @Override
    public List<Medico> findActiveForSelect() {
        return pool.withConnection(connection -> {
            List<Medico> items = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(SELECT + " WHERE activo = ?" + ORDER)) {
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

    /** Returns the typed exception when there is one; otherwise the original SQLException is rethrown. */
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

    /** Binds the seven entity columns starting at index 1; returns the next free index. */
    private static int bindEntity(PreparedStatement ps, Medico medico) throws SQLException {
        ps.setString(1, medico.documento());
        ps.setString(2, medico.nombres());
        ps.setString(3, medico.apellidos());
        ps.setString(4, medico.especialidad());
        DaoSupport.setNullable(ps, 5, medico.telefono());
        DaoSupport.setNullable(ps, 6, medico.correo());
        ps.setBoolean(7, medico.activo());
        return 8;
    }

    private static Optional<Medico> queryOne(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
        }
    }

    private static Medico map(ResultSet rs) throws SQLException {
        return new Medico(
            rs.getLong("id"),
            rs.getString("documento"),
            rs.getString("nombres"),
            rs.getString("apellidos"),
            rs.getString("especialidad"),
            rs.getString("telefono"),
            rs.getString("correo"),
            rs.getBoolean("activo"));
    }
}
