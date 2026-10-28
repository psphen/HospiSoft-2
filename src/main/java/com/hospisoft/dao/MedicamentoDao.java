package com.hospisoft.dao;

import com.hospisoft.db.ConnectionPool;
import com.hospisoft.model.Medicamento;
import com.hospisoft.model.Page;
import com.hospisoft.model.StockState;
import com.hospisoft.service.ConflictException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation of {@link MedicamentoRepository}. PreparedStatement only; the WHERE clause is
 * assembled from constant fragments (the stock state is a fixed SQL expression per enum value).
 * Constraint violations are translated like in {@link MedicoDao}.
 */
public final class MedicamentoDao implements MedicamentoRepository {

    static final String COLUMNS = "id, codigo, nombre, descripcion, presentacion, stock, stock_minimo, activo";

    static final String SELECT = "SELECT " + COLUMNS + " FROM medicamento";

    static final String COUNT = "SELECT count(*) FROM medicamento";

    static final String INSERT = """
        INSERT INTO medicamento (codigo, nombre, descripcion, presentacion, stock, stock_minimo, activo)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """;

    /** Optimistic concurrency on stock: the row must still hold the stock the client loaded. */
    static final String UPDATE = """
        UPDATE medicamento
        SET codigo = ?, nombre = ?, descripcion = ?, presentacion = ?, stock = ?, stock_minimo = ?, activo = ?
        WHERE id = ? AND stock = ?
        """;

    static final String EXISTS_BY_ID = "SELECT EXISTS (SELECT 1 FROM medicamento WHERE id = ?)";

    static final String SET_ACTIVO = "UPDATE medicamento SET activo = ? WHERE id = ?";

    static final String DELETE = "DELETE FROM medicamento WHERE id = ?";

    static final String HAS_HISTORY = "SELECT EXISTS (SELECT 1 FROM receta WHERE id_medicamento = ?)";

    static final String COUNT_LOW_STOCK = "SELECT count(*) FROM medicamento WHERE stock <= stock_minimo AND activo";

    static final String TEXT_CLAUSE =
        "(codigo ILIKE ? ESCAPE '\\' OR nombre ILIKE ? ESCAPE '\\' OR presentacion ILIKE ? ESCAPE '\\')";

    static final String STATE_AGOTADO = "stock = 0";
    static final String STATE_STOCK_BAJO = "stock > 0 AND stock <= stock_minimo";
    static final String STATE_DISPONIBLE = "stock > stock_minimo";

    static final String ORDER = " ORDER BY nombre, id";

    static final String PAGING = " LIMIT ? OFFSET ?";

    private static final String ENTITY = "Medicamento";

    private final ConnectionPool pool;

    public MedicamentoDao(ConnectionPool pool) {
        this.pool = pool;
    }

    /** Constant SQL fragment for a stock state (no parameters). */
    static String stateClause(StockState state) {
        return switch (state) {
            case AGOTADO -> STATE_AGOTADO;
            case STOCK_BAJO -> STATE_STOCK_BAJO;
            case DISPONIBLE -> STATE_DISPONIBLE;
        };
    }

    @Override
    public Page<Medicamento> findPage(MedicamentoFilter filter, int page, int pageSize) {
        MedicamentoFilter safe = filter == null ? MedicamentoFilter.NONE : filter;
        long offset = Filters.offset(page, pageSize);
        return pool.withConnection(connection -> {
            List<String> clauses = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            if (safe.texto() != null) {
                clauses.add(TEXT_CLAUSE);
                String pattern = Filters.likePattern(safe.texto());
                params.add(pattern);
                params.add(pattern);
                params.add(pattern);
            }
            if (safe.activo() != null) {
                clauses.add("activo = ?");
                params.add(safe.activo());
            }
            if (safe.stockState() != null) {
                clauses.add(stateClause(safe.stockState()));
            }
            String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);

            long total = count(connection, COUNT + where, params);
            List<Medicamento> items = new ArrayList<>();
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

    @Override
    public Optional<Medicamento> findById(long id) {
        return pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(SELECT + " WHERE id = ?")) {
                ps.setLong(1, id);
                return queryOne(ps);
            }
        });
    }

    @Override
    public Optional<Medicamento> findByCodigo(String codigo) {
        return pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(SELECT + " WHERE codigo = ?")) {
                ps.setString(1, codigo);
                return queryOne(ps);
            }
        });
    }

    @Override
    public long insert(Medicamento medicamento) {
        return pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
                bindEntity(ps, medicamento);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("INSERT did not return an id");
                    }
                    return rs.getLong(1);
                }
            } catch (SQLException ex) {
                throw rethrow(ex, duplicateCodigo(ex));
            }
        });
    }

    @Override
    public void update(Medicamento medicamento, int stockExpected) {
        pool.withConnection(connection -> {
            int affected;
            try (PreparedStatement ps = connection.prepareStatement(UPDATE)) {
                int index = bindEntity(ps, medicamento);
                ps.setLong(index++, medicamento.id());
                ps.setInt(index, stockExpected);
                affected = ps.executeUpdate();
            } catch (SQLException ex) {
                throw rethrow(ex, duplicateCodigo(ex));
            }
            if (affected == 0) {
                // same connection: a nested pool call could exhaust a one-connection pool
                try (PreparedStatement ps = connection.prepareStatement(EXISTS_BY_ID)) {
                    ps.setLong(1, medicamento.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        boolean exists = rs.next() && rs.getBoolean(1);
                        DaoSupport.requireAffected(exists ? 1 : 0, ENTITY, medicamento.id());
                    }
                }
                throw new ConflictException("Medicamento " + medicamento.id() + " stock changed");
            }
            return null;
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
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        });
    }

    @Override
    public long countLowStock() {
        return pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(COUNT_LOW_STOCK);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    @Override
    public List<Medicamento> findAllActive() {
        return pool.withConnection(connection -> {
            List<Medicamento> items = new ArrayList<>();
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

    private static RuntimeException duplicateCodigo(SQLException ex) {
        return DaoSupport.duplicateKey(ex, "codigo");
    }

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
    private static int bindEntity(PreparedStatement ps, Medicamento m) throws SQLException {
        ps.setString(1, m.codigo());
        ps.setString(2, m.nombre());
        DaoSupport.setNullable(ps, 3, m.descripcion());
        ps.setString(4, m.presentacion());
        ps.setInt(5, m.stock());
        ps.setInt(6, m.stockMinimo());
        ps.setBoolean(7, m.activo());
        return 8;
    }

    private static Optional<Medicamento> queryOne(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
        }
    }

    private static Medicamento map(ResultSet rs) throws SQLException {
        return new Medicamento(
            rs.getLong("id"),
            rs.getString("codigo"),
            rs.getString("nombre"),
            rs.getString("descripcion"),
            rs.getString("presentacion"),
            rs.getInt("stock"),
            rs.getInt("stock_minimo"),
            rs.getBoolean("activo"));
    }
}
