package com.hospisoft.dao;

import com.hospisoft.db.ConnectionPool;
import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.HistoriaClinica;
import com.hospisoft.model.HistoriaView;
import com.hospisoft.model.Page;
import com.hospisoft.model.Receta;
import com.hospisoft.model.RecetaView;
import com.hospisoft.service.ConflictException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * JDBC implementation of {@link AtencionRepository}.
 *
 * <p>Lock order inside a transaction: the medico row first, then every prescribed medicamento in
 * ASCENDING id order. Two concurrent closings therefore take the same locks in the same sequence
 * and cannot deadlock. {@code FOR NO KEY UPDATE} is used so foreign-key key-share locks taken by
 * other inserts are not blocked.
 */
public final class AtencionDao implements AtencionRepository {

    static final String HISTORIA_COLUMNS = """
        h.id, h.id_cita, h.id_paciente, h.id_medico, h.fecha, h.diagnostico, h.tratamiento, h.notas,
               m.nombres AS medico_nombres, m.apellidos AS medico_apellidos, m.especialidad,
               p.nombres AS paciente_nombres, p.apellidos AS paciente_apellidos, p.documento AS paciente_documento""";

    static final String HISTORIA_FROM =
        " FROM historia_clinica h JOIN medico m ON m.id = h.id_medico JOIN paciente p ON p.id = h.id_paciente";

    static final String SELECT_HISTORIA = "SELECT " + HISTORIA_COLUMNS + HISTORIA_FROM;

    static final String COUNT_HISTORIA = "SELECT count(*)" + HISTORIA_FROM;

    static final String HISTORIA_TEXT_CLAUSE =
        "(h.diagnostico ILIKE ? ESCAPE '\\' OR m.nombres ILIKE ? ESCAPE '\\' OR m.apellidos ILIKE ? ESCAPE '\\')";

    static final String HISTORIA_ORDER = " ORDER BY h.fecha DESC, h.id DESC";

    static final String PAGING = " LIMIT ? OFFSET ?";

    static final String RECETA_COLUMNS = """
        r.id, r.id_historia, r.id_medicamento, r.cantidad, r.dosis, r.duracion, r.entregado,
               r.observacion_no_disponible, md.codigo, md.nombre, md.presentacion""";

    static final String SELECT_RECETAS_BY_HISTORIA = "SELECT " + RECETA_COLUMNS
        + " FROM receta r JOIN medicamento md ON md.id = r.id_medicamento WHERE r.id_historia = ? ORDER BY r.id";

    static final String RECETA_PAGE_FROM =
        " FROM receta r JOIN medicamento md ON md.id = r.id_medicamento"
            + " JOIN historia_clinica h ON h.id = r.id_historia WHERE h.id_paciente = ?";

    /** Historias newest first, but the lines of one historia keep their insertion order. */
    static final String SELECT_RECETAS_PAGE = "SELECT " + RECETA_COLUMNS + ", h.fecha" + RECETA_PAGE_FROM
        + " ORDER BY h.fecha DESC, h.id DESC, r.id" + PAGING;

    static final String COUNT_RECETAS_PAGE = "SELECT count(*)" + RECETA_PAGE_FROM;

    static final String LOCK_MEDICO = "SELECT activo FROM medico WHERE id = ? FOR NO KEY UPDATE";

    static final String LOCK_MEDICAMENTO =
        "SELECT stock, stock_minimo, activo FROM medicamento WHERE id = ? FOR NO KEY UPDATE";

    static final String EXISTS_EN_CURSO =
        "SELECT EXISTS (SELECT 1 FROM cita WHERE id_medico = ? AND estado = 'EN_CURSO' AND id <> ?)";

    static final String ESTADO_OF_OWN_CITA = "SELECT estado FROM cita WHERE id = ? AND id_medico = ?";

    static final String INICIAR = """
        UPDATE cita SET estado = 'EN_CURSO', hora_inicio_real = now()
        WHERE id = ? AND id_medico = ? AND estado = 'PROGRAMADA'
        """;

    static final String FINALIZAR = """
        UPDATE cita SET estado = 'FINALIZADA', hora_fin_real = now(), observaciones = ?
        WHERE id = ? AND id_medico = ? AND estado = 'EN_CURSO'
        """;

    static final String INSERT_HISTORIA = """
        INSERT INTO historia_clinica (id_cita, id_paciente, id_medico, diagnostico, tratamiento, notas)
        VALUES (?, ?, ?, ?, ?, ?)
        RETURNING id
        """;

    static final String INSERT_RECETA = """
        INSERT INTO receta (id_historia, id_medicamento, cantidad, dosis, duracion, entregado, observacion_no_disponible)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """;

    static final String DECREMENT_STOCK = "UPDATE medicamento SET stock = stock - ? WHERE id = ? AND stock >= ?";

    /** The cita already has a historia: id_cita is UNIQUE. */
    static final String HISTORIA_UNIQUE_CONSTRAINT = "historia_clinica_id_cita_key";

    /** Partial unique index: one EN_CURSO cita per medico (defense in depth for the service check). */
    static final String EN_CURSO_UNIQUE_INDEX = "cita_un_en_curso_por_medico";

    /** Message of the service rule the partial unique index also enforces. */
    public static final String ALREADY_EN_CURSO = com.hospisoft.service.AtencionService.ALREADY_EN_CURSO;

    /** Message of the service rule this DAO also enforces at the database level. */
    public static final String ALREADY_CLOSED = com.hospisoft.service.AtencionService.ALREADY_CLOSED;

    private final ConnectionPool pool;

    public AtencionDao(ConnectionPool pool) {
        this.pool = pool;
    }

    @Override
    public <T> T inTransaction(Function<AtencionTx, T> work) {
        return pool.inTransaction(connection -> work.apply(new Tx(connection)));
    }

    // ------------------------------------------------------------ reads

    @Override
    public Optional<HistoriaView> findHistoriaByCita(long idCita) {
        return pool.withConnection(connection ->
            withRecetas(connection, queryOneHistoria(connection, SELECT_HISTORIA + " WHERE h.id_cita = ?", idCita)));
    }

    @Override
    public Optional<HistoriaView> findHistoriaById(long idHistoria) {
        return pool.withConnection(connection ->
            withRecetas(connection, queryOneHistoria(connection, SELECT_HISTORIA + " WHERE h.id = ?", idHistoria)));
    }

    @Override
    public Page<HistoriaView> findHistoriasPage(HistoriaQuery query, int page, int pageSize) {
        HistoriaQuery q = query == null ? HistoriaQuery.NONE : query;
        long offset = Filters.offset(page, pageSize);
        return pool.withConnection(connection -> {
            List<String> clauses = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            if (q.idPaciente() != null) {
                clauses.add("h.id_paciente = ?");
                params.add(q.idPaciente());
            }
            if (q.idMedico() != null) {
                clauses.add("h.id_medico = ?");
                params.add(q.idMedico());
            }
            if (q.texto() != null) {
                clauses.add(HISTORIA_TEXT_CLAUSE);
                String pattern = Filters.likePattern(q.texto());
                params.add(pattern);
                params.add(pattern);
                params.add(pattern);
            }
            String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);

            long total;
            try (PreparedStatement ps = connection.prepareStatement(COUNT_HISTORIA + where)) {
                DaoSupport.bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }
            List<HistoriaView> items = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(SELECT_HISTORIA + where + HISTORIA_ORDER + PAGING)) {
                int index = DaoSupport.bind(ps, params);
                ps.setLong(index++, pageSize);
                ps.setLong(index, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(mapHistoria(rs));
                    }
                }
            }
            return new Page<>(items, total, page, pageSize);
        });
    }

    @Override
    public List<RecetaView> findRecetasByHistoria(long idHistoria) {
        return pool.withConnection(connection -> recetasOf(connection, idHistoria));
    }

    @Override
    public Page<RecetaView> findRecetasPage(long idPaciente, int page, int pageSize) {
        long offset = Filters.offset(page, pageSize);
        return pool.withConnection(connection -> {
            long total;
            try (PreparedStatement ps = connection.prepareStatement(COUNT_RECETAS_PAGE)) {
                ps.setLong(1, idPaciente);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }
            List<RecetaView> items = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(SELECT_RECETAS_PAGE)) {
                ps.setLong(1, idPaciente);
                ps.setLong(2, pageSize);
                ps.setLong(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(mapReceta(rs, true));
                    }
                }
            }
            return new Page<>(items, total, page, pageSize);
        });
    }

    /** Attaches the recetas to a historia found on the same connection. */
    private static Optional<HistoriaView> withRecetas(Connection connection, Optional<HistoriaView> found)
        throws SQLException {
        if (found.isEmpty()) {
            return found;
        }
        return Optional.of(found.get().withRecetas(recetasOf(connection, found.get().historia().id())));
    }

    private static List<RecetaView> recetasOf(Connection connection, long idHistoria) throws SQLException {
        List<RecetaView> items = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_RECETAS_BY_HISTORIA)) {
            ps.setLong(1, idHistoria);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(mapReceta(rs, false));
                }
            }
        }
        return items;
    }

    private static Optional<HistoriaView> queryOneHistoria(Connection connection, String sql, long id)
        throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapHistoria(rs)) : Optional.empty();
            }
        }
    }

    // ------------------------------------------------------------ transaction scope

    private static final class Tx implements AtencionTx {
        private final Connection connection;

        Tx(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Optional<Boolean> lockMedico(long idMedico) {
            try (PreparedStatement ps = connection.prepareStatement(LOCK_MEDICO)) {
                ps.setLong(1, idMedico);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getBoolean(1)) : Optional.empty();
                }
            } catch (SQLException ex) {
                throw sneaky(ex);
            }
        }

        @Override
        public Optional<MedicamentoLock> lockMedicamento(long idMedicamento) {
            try (PreparedStatement ps = connection.prepareStatement(LOCK_MEDICAMENTO)) {
                ps.setLong(1, idMedicamento);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new MedicamentoLock(idMedicamento, rs.getInt("stock"),
                        rs.getInt("stock_minimo"), rs.getBoolean("activo")));
                }
            } catch (SQLException ex) {
                throw sneaky(ex);
            }
        }

        @Override
        public boolean existsEnCurso(long idMedico, Long excludeCitaId) {
            try (PreparedStatement ps = connection.prepareStatement(EXISTS_EN_CURSO)) {
                ps.setLong(1, idMedico);
                ps.setLong(2, excludeCitaId == null ? -1L : excludeCitaId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            } catch (SQLException ex) {
                throw sneaky(ex);
            }
        }

        @Override
        public Optional<EstadoCita> estadoOfOwnCita(long idCita, long idMedico) {
            try (PreparedStatement ps = connection.prepareStatement(ESTADO_OF_OWN_CITA)) {
                ps.setLong(1, idCita);
                ps.setLong(2, idMedico);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(EstadoCita.valueOf(rs.getString(1))) : Optional.empty();
                }
            } catch (SQLException ex) {
                throw sneaky(ex);
            }
        }

        @Override
        public int iniciar(long idCita, long idMedico) {
            try (PreparedStatement ps = connection.prepareStatement(INICIAR)) {
                ps.setLong(1, idCita);
                ps.setLong(2, idMedico);
                return ps.executeUpdate();
            } catch (SQLException ex) {
                if (DaoSupport.UNIQUE_VIOLATION.equals(ex.getSQLState())
                    && String.valueOf(ex.getMessage()).contains(EN_CURSO_UNIQUE_INDEX)) {
                    throw new com.hospisoft.service.ValidationException(
                        java.util.Map.of("estado", ALREADY_EN_CURSO));
                }
                throw sneaky(ex);
            }
        }

        @Override
        public int finalizar(long idCita, long idMedico, String observaciones) {
            try (PreparedStatement ps = connection.prepareStatement(FINALIZAR)) {
                DaoSupport.setNullable(ps, 1, observaciones);
                ps.setLong(2, idCita);
                ps.setLong(3, idMedico);
                return ps.executeUpdate();
            } catch (SQLException ex) {
                throw sneaky(ex);
            }
        }

        @Override
        public long insertHistoria(long idCita, long idPaciente, long idMedico, String diagnostico,
                                   String tratamiento, String notas) {
            try (PreparedStatement ps = connection.prepareStatement(INSERT_HISTORIA)) {
                ps.setLong(1, idCita);
                ps.setLong(2, idPaciente);
                ps.setLong(3, idMedico);
                ps.setString(4, diagnostico);
                DaoSupport.setNullable(ps, 5, tratamiento);
                DaoSupport.setNullable(ps, 6, notas);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("INSERT did not return an id");
                    }
                    return rs.getLong(1);
                }
            } catch (SQLException ex) {
                if (DaoSupport.UNIQUE_VIOLATION.equals(ex.getSQLState())
                    && String.valueOf(ex.getMessage()).contains(HISTORIA_UNIQUE_CONSTRAINT)) {
                    // id_cita is UNIQUE: another session closed this cita first
                    throw new ConflictException("Cita " + idCita + " already has a historia", ALREADY_CLOSED);
                }
                throw sneaky(ex);
            }
        }

        @Override
        public long insertReceta(long idHistoria, long idMedicamento, int cantidad, String dosis, String duracion,
                                 boolean entregado, String observacionNoDisponible) {
            try (PreparedStatement ps = connection.prepareStatement(INSERT_RECETA)) {
                ps.setLong(1, idHistoria);
                ps.setLong(2, idMedicamento);
                ps.setInt(3, cantidad);
                DaoSupport.setNullable(ps, 4, dosis);
                DaoSupport.setNullable(ps, 5, duracion);
                ps.setBoolean(6, entregado);
                DaoSupport.setNullable(ps, 7, observacionNoDisponible);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("INSERT did not return an id");
                    }
                    return rs.getLong(1);
                }
            } catch (SQLException ex) {
                RuntimeException typed = DaoSupport.foreignKeyViolation(ex,
                    java.util.Map.of("id_medicamento", "idMedicamento"));
                throw typed != null ? typed : sneaky(ex);
            }
        }

        @Override
        public int decrementStock(long idMedicamento, int cantidad) {
            try (PreparedStatement ps = connection.prepareStatement(DECREMENT_STOCK)) {
                ps.setInt(1, cantidad);
                ps.setLong(2, idMedicamento);
                ps.setInt(3, cantidad);
                return ps.executeUpdate();
            } catch (SQLException ex) {
                throw sneaky(ex);
            }
        }
    }

    /** The Tx interface declares no checked exceptions; the pool classifies SQLExceptions. */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(Throwable ex) throws E {
        throw (E) ex;
    }

    // ------------------------------------------------------------ mapping

    static HistoriaView mapHistoria(ResultSet rs) throws SQLException {
        HistoriaClinica historia = new HistoriaClinica(
            rs.getLong("id"),
            rs.getLong("id_cita"),
            rs.getLong("id_paciente"),
            rs.getLong("id_medico"),
            rs.getObject("fecha", OffsetDateTime.class),
            rs.getString("diagnostico"),
            rs.getString("tratamiento"),
            rs.getString("notas"));
        return new HistoriaView(historia,
            rs.getString("medico_nombres"), rs.getString("medico_apellidos"), rs.getString("especialidad"),
            rs.getString("paciente_nombres"), rs.getString("paciente_apellidos"), rs.getString("paciente_documento"),
            List.of());
    }

    static RecetaView mapReceta(ResultSet rs, boolean withFecha) throws SQLException {
        Receta receta = new Receta(
            rs.getLong("id"),
            rs.getLong("id_historia"),
            rs.getLong("id_medicamento"),
            rs.getInt("cantidad"),
            rs.getString("dosis"),
            rs.getString("duracion"),
            rs.getBoolean("entregado"),
            rs.getString("observacion_no_disponible"),
            withFecha ? rs.getObject("fecha", OffsetDateTime.class) : null);
        return new RecetaView(receta, rs.getString("codigo"), rs.getString("nombre"), rs.getString("presentacion"));
    }
}
