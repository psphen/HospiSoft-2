package com.hospisoft.dao;

import com.hospisoft.db.ConnectionPool;
import com.hospisoft.model.Cita;
import com.hospisoft.model.CitaView;
import com.hospisoft.model.EstadoCita;
import com.hospisoft.model.Page;
import com.hospisoft.service.CitaService;
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
 * JDBC implementation of {@link CitaRepository}. Rows are joined with medico and paciente for
 * display. Timestamps are bound and read as {@link OffsetDateTime} (TIMESTAMPTZ). The overlap
 * checks use the slot length {@link CitaService#SLOT_MINUTES} passed as a parameter.
 */
public final class CitaDao implements CitaRepository {

    static final String COLUMNS = """
        c.id, c.id_medico, c.id_paciente, c.fecha_hora_programada, c.motivo, c.estado,
               c.hora_inicio_real, c.hora_fin_real, c.observaciones,
               m.nombres AS medico_nombres, m.apellidos AS medico_apellidos, m.especialidad,
               p.nombres AS paciente_nombres, p.apellidos AS paciente_apellidos, p.documento AS paciente_documento""";

    static final String FROM = " FROM cita c JOIN medico m ON m.id = c.id_medico JOIN paciente p ON p.id = c.id_paciente";

    static final String SELECT = "SELECT " + COLUMNS + FROM;

    static final String COUNT = "SELECT count(*)" + FROM;

    static final String TEXT_CLAUSE =
        "(p.nombres ILIKE ? ESCAPE '\\' OR p.apellidos ILIKE ? ESCAPE '\\' OR (p.nombres || ' ' || p.apellidos) ILIKE ? ESCAPE '\\'"
            + " OR p.documento ILIKE ? ESCAPE '\\' OR m.nombres ILIKE ? ESCAPE '\\' OR m.apellidos ILIKE ? ESCAPE '\\'"
            + " OR (m.nombres || ' ' || m.apellidos) ILIKE ? ESCAPE '\\')";

    static final String ORDER = " ORDER BY c.fecha_hora_programada, c.id";

    static final String PAGING = " LIMIT ? OFFSET ?";

    static final String INSERT = """
        INSERT INTO cita (id_medico, id_paciente, fecha_hora_programada, motivo, estado)
        VALUES (?, ?, ?, ?, 'PROGRAMADA')
        RETURNING id
        """;

    static final String RESCHEDULE = """
        UPDATE cita SET id_medico = ?, id_paciente = ?, fecha_hora_programada = ?, motivo = ?
        WHERE id = ? AND estado = 'PROGRAMADA'
        """;

    static final String CANCEL = "UPDATE cita SET estado = 'CANCELADA' WHERE id = ? AND estado = 'PROGRAMADA'";

    static final String EXISTS_BY_ID = "SELECT EXISTS (SELECT 1 FROM cita WHERE id = ?)";

    /** NO KEY UPDATE: blocks concurrent schedulers on the same row without blocking FK key-share locks. */
    static final String LOCK_MEDICO = "SELECT activo FROM medico WHERE id = ? FOR NO KEY UPDATE";

    static final String LOCK_PACIENTE = "SELECT activo FROM paciente WHERE id = ? FOR NO KEY UPDATE";

    static final java.util.Map<String, String> FK_FIELDS = java.util.Map.of("id_medico", "idMedico", "id_paciente", "idPaciente");

    /** Intersection of [fecha, fecha + slot) with [?, ?): existing.start < newEnd AND existing.end > newStart. */
    static final String OVERLAP_MEDICO = """
        SELECT EXISTS (SELECT 1 FROM cita
            WHERE id_medico = ? AND estado <> 'CANCELADA' AND id <> ?
              AND fecha_hora_programada < ? AND fecha_hora_programada + make_interval(mins => ?) > ?)
        """;

    static final String OVERLAP_PACIENTE = """
        SELECT EXISTS (SELECT 1 FROM cita
            WHERE id_paciente = ? AND estado <> 'CANCELADA' AND id <> ?
              AND fecha_hora_programada < ? AND fecha_hora_programada + make_interval(mins => ?) > ?)
        """;

    static final String AGENDA = SELECT
        + " WHERE c.id_medico = ? AND c.fecha_hora_programada >= ? AND c.fecha_hora_programada < ?" + ORDER;

    private static final String ENTITY = "Cita";

    private final ConnectionPool pool;

    public CitaDao(ConnectionPool pool) {
        this.pool = pool;
    }

    @Override
    public <T> T inTransaction(Function<CitaTx, T> work) {
        return pool.inTransaction(connection -> work.apply(new Tx(connection)));
    }

    @Override
    public Page<CitaView> findPage(CitaQuery query, int page, int pageSize) {
        CitaQuery q = query == null ? CitaQuery.NONE : query;
        long offset = Filters.offset(page, pageSize);
        return pool.withConnection(connection -> {
            List<String> clauses = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            if (q.texto() != null) {
                clauses.add(TEXT_CLAUSE);
                String pattern = Filters.likePattern(q.texto());
                for (int i = 0; i < 7; i++) {
                    params.add(pattern);
                }
            }
            if (q.dayStart() != null && q.dayEnd() != null) {
                clauses.add("c.fecha_hora_programada >= ? AND c.fecha_hora_programada < ?");
                params.add(q.dayStart());
                params.add(q.dayEnd());
            }
            if (q.estado() != null) {
                clauses.add("c.estado = ?");
                params.add(q.estado().name());
            }
            if (q.idMedico() != null) {
                clauses.add("c.id_medico = ?");
                params.add(q.idMedico());
            }
            if (q.idPaciente() != null) {
                clauses.add("c.id_paciente = ?");
                params.add(q.idPaciente());
            }
            if (q.from() != null) {
                clauses.add("c.fecha_hora_programada >= ?");
                params.add(q.from());
            }
            if (q.to() != null) {
                clauses.add("c.fecha_hora_programada < ?");
                params.add(q.to());
            }
            String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);

            long total;
            try (PreparedStatement ps = connection.prepareStatement(COUNT + where)) {
                DaoSupport.bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }
            List<CitaView> items = new ArrayList<>();
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
    public Optional<CitaView> findById(long id) {
        return pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(SELECT + " WHERE c.id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public void cancel(long id) {
        pool.withConnection(connection -> {
            int affected;
            try (PreparedStatement ps = connection.prepareStatement(CANCEL)) {
                ps.setLong(1, id);
                affected = ps.executeUpdate();
            }
            if (affected == 0) {
                requireExistsThenConflict(connection, id, "state is not PROGRAMADA");
            }
            return null;
        });
    }

    @Override
    public List<CitaView> findByMedicoAndDay(long idMedico, OffsetDateTime dayStart, OffsetDateTime dayEnd) {
        return pool.withConnection(connection -> {
            List<CitaView> items = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(AGENDA)) {
                ps.setLong(1, idMedico);
                ps.setObject(2, dayStart);
                ps.setObject(3, dayEnd);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(map(rs));
                    }
                }
            }
            return items;
        });
    }

    // ------------------------------------------------------------ transaction scope

    private static final class Tx implements CitaTx {
        private final Connection connection;

        Tx(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Optional<Boolean> lockMedico(long idMedico) {
            return lock(LOCK_MEDICO, idMedico);
        }

        @Override
        public Optional<Boolean> lockPaciente(long idPaciente) {
            return lock(LOCK_PACIENTE, idPaciente);
        }

        private Optional<Boolean> lock(String sql, long id) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getBoolean(1)) : Optional.empty();
                }
            } catch (SQLException ex) {
                throw sneaky(ex);
            }
        }

        @Override
        public boolean existsOverlapForMedico(long idMedico, OffsetDateTime start, OffsetDateTime end, Long excludeId) {
            return existsOverlap(OVERLAP_MEDICO, idMedico, start, end, excludeId);
        }

        @Override
        public boolean existsOverlapForPaciente(long idPaciente, OffsetDateTime start, OffsetDateTime end, Long excludeId) {
            return existsOverlap(OVERLAP_PACIENTE, idPaciente, start, end, excludeId);
        }

        private boolean existsOverlap(String sql, long owner, OffsetDateTime start, OffsetDateTime end, Long excludeId) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, owner);
                ps.setLong(2, excludeId == null ? -1L : excludeId);
                ps.setObject(3, end);
                ps.setInt(4, CitaService.SLOT_MINUTES);
                ps.setObject(5, start);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            } catch (SQLException ex) {
                throw sneaky(ex);
            }
        }

        @Override
        public long insert(Cita cita) {
            try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
                ps.setLong(1, cita.idMedico());
                ps.setLong(2, cita.idPaciente());
                ps.setObject(3, cita.fechaHoraProgramada());
                ps.setString(4, cita.motivo());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("INSERT did not return an id");
                    }
                    return rs.getLong(1);
                }
            } catch (SQLException ex) {
                RuntimeException typed = DaoSupport.foreignKeyViolation(ex, FK_FIELDS);
                throw typed != null ? typed : sneaky(ex);
            }
        }

        @Override
        public void reschedule(long id, long idMedico, long idPaciente, OffsetDateTime fechaHora, String motivo) {
            try {
                int affected;
                try (PreparedStatement ps = connection.prepareStatement(RESCHEDULE)) {
                    ps.setLong(1, idMedico);
                    ps.setLong(2, idPaciente);
                    ps.setObject(3, fechaHora);
                    ps.setString(4, motivo);
                    ps.setLong(5, id);
                    affected = ps.executeUpdate();
                }
                if (affected == 0) {
                    requireExistsThenConflict(connection, id, "state is not PROGRAMADA");
                }
            } catch (SQLException ex) {
                RuntimeException typed = DaoSupport.foreignKeyViolation(ex, FK_FIELDS);
                throw typed != null ? typed : sneaky(ex);
            }
        }
    }

    /**
     * The CitaTx interface has no checked exceptions; the pool's {@code inTransaction} classifies
     * SQLExceptions, so they are rethrown unchanged through this generic trick.
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(Throwable ex) throws E {
        throw (E) ex;
    }

    private static void requireExistsThenConflict(Connection connection, long id, String detail) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(EXISTS_BY_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                boolean exists = rs.next() && rs.getBoolean(1);
                DaoSupport.requireAffected(exists ? 1 : 0, ENTITY, id);
            }
        }
        throw new ConflictException("Cita " + id + " " + detail);
    }

    static CitaView map(ResultSet rs) throws SQLException {
        Cita cita = new Cita(
            rs.getLong("id"),
            rs.getLong("id_medico"),
            rs.getLong("id_paciente"),
            rs.getObject("fecha_hora_programada", OffsetDateTime.class),
            rs.getString("motivo"),
            EstadoCita.valueOf(rs.getString("estado")),
            rs.getObject("hora_inicio_real", OffsetDateTime.class),
            rs.getObject("hora_fin_real", OffsetDateTime.class),
            rs.getString("observaciones"));
        return new CitaView(cita,
            rs.getString("medico_nombres"),
            rs.getString("medico_apellidos"),
            rs.getString("especialidad"),
            rs.getString("paciente_nombres"),
            rs.getString("paciente_apellidos"),
            rs.getString("paciente_documento"));
    }
}
