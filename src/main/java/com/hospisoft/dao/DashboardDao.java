package com.hospisoft.dao;

import com.hospisoft.db.ConnectionPool;
import com.hospisoft.model.CitaView;
import com.hospisoft.model.EstadoCita;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC implementation of {@link DashboardRepository}: every aggregate is a constant statement with
 * parameters, and the whole snapshot runs on one borrowed connection (read-only, no transaction).
 */
public final class DashboardDao implements DashboardRepository {

    static final String COUNT_MEDICOS_ACTIVOS = "SELECT count(*) FROM medico WHERE activo";

    static final String COUNT_PACIENTES_ACTIVOS = "SELECT count(*) FROM paciente WHERE activo";

    static final String COUNT_MEDICAMENTOS_ACTIVOS = "SELECT count(*) FROM medicamento WHERE activo";

    /** Distinct patients a medico has seen: cancelled citas do not count (phase 4 scope rule). */
    static final String COUNT_PACIENTES_DE_MEDICO =
        "SELECT count(DISTINCT id_paciente) FROM cita WHERE id_medico = ? AND estado <> 'CANCELADA'";

    static final String COUNT_CITAS_DE_PACIENTE = "SELECT count(*) FROM cita WHERE id_paciente = ?";

    static final String COUNT_RECETAS_DE_PACIENTE =
        "SELECT count(*) FROM receta r JOIN historia_clinica h ON h.id = r.id_historia WHERE h.id_paciente = ?";

    static final String COUNT_HISTORIAS_DE_PACIENTE =
        "SELECT count(*) FROM historia_clinica WHERE id_paciente = ?";

    static final String COUNT_CITAS_DEL_DIA =
        "SELECT count(*) FROM cita WHERE fecha_hora_programada >= ? AND fecha_hora_programada < ?";

    static final String COUNT_CITAS_PENDIENTES =
        "SELECT count(*) FROM cita WHERE estado = 'PROGRAMADA' AND fecha_hora_programada >= ?";

    static final String RESUMEN_DEL_DIA = """
        SELECT estado, count(*) FROM cita
        WHERE fecha_hora_programada >= ? AND fecha_hora_programada < ?""";

    static final String RESUMEN_GROUP_BY = " GROUP BY estado";

    static final String DURACION_PROMEDIO = """
        SELECT AVG(EXTRACT(EPOCH FROM (hora_fin_real - hora_inicio_real)) / 60) FROM cita
        WHERE estado = 'FINALIZADA' AND hora_inicio_real IS NOT NULL AND hora_fin_real IS NOT NULL
          AND fecha_hora_programada >= ? AND fecha_hora_programada < ?""";

    /** Only PROGRAMADA, so the list agrees with the "citas pendientes" KPI. */
    static final String PROXIMAS_CITAS = CitaDao.SELECT
        + " WHERE c.estado = 'PROGRAMADA' AND c.fecha_hora_programada >= ?";

    static final String PROXIMAS_ORDER = CitaDao.ORDER + " LIMIT ?";

    private final ConnectionPool pool;

    public DashboardDao(ConnectionPool pool) {
        this.pool = pool;
    }

    /** Scope fragment appended to a WHERE that already has a condition. */
    static String scopeClause(Scope scope, String alias) {
        if (scope.idMedico() != null) {
            return " AND " + alias + "id_medico = ?";
        }
        if (scope.idPaciente() != null) {
            return " AND " + alias + "id_paciente = ?";
        }
        return "";
    }

    /** The single scope parameter, or null when the scope is global. */
    private static Long scopeParam(Scope scope) {
        return scope.idMedico() != null ? scope.idMedico() : scope.idPaciente();
    }

    /** Upper bound for the upcoming list: the panel shows a handful of rows, never a full agenda. */
    static final int MAX_LIMIT = 50;

    /** PostgreSQL rejects a negative LIMIT, so the value is clamped before it reaches the driver. */
    static int safeLimit(int limit) {
        return Math.max(0, Math.min(limit, MAX_LIMIT));
    }

    @Override
    public DashboardSnapshot snapshot(Scope scope, OffsetDateTime dayStart, OffsetDateTime dayEnd,
                                      OffsetDateTime now, int limit) {
        Scope safe = scope == null ? Scope.ALL : scope;
        Long scopeId = scopeParam(safe);
        String countScope = scopeClause(safe, "");
        String joinedScope = scopeClause(safe, "c.");

        return pool.withConnection(connection -> {
            long citasHoy = count(connection, COUNT_CITAS_DEL_DIA + countScope, dayStart, dayEnd, scopeId);
            long citasPendientes = count(connection, COUNT_CITAS_PENDIENTES + countScope, now, null, scopeId);
            EstadoCounts resumen = resumen(connection, safe, dayStart, dayEnd, scopeId, countScope);
            Double duracion = duracion(connection, dayStart, dayEnd, scopeId, countScope);
            List<CitaView> proximas = proximas(connection, now, scopeId, joinedScope, limit);

            long medicosActivos = 0;
            long pacientesActivos = 0;
            long medicamentosActivos = 0;
            if (safe.isAll()) {
                medicosActivos = count(connection, COUNT_MEDICOS_ACTIVOS, null, null, null);
                pacientesActivos = count(connection, COUNT_PACIENTES_ACTIVOS, null, null, null);
                medicamentosActivos = count(connection, COUNT_MEDICAMENTOS_ACTIVOS, null, null, null);
            }
            long pacientesAtendidos = 0;
            if (safe.idMedico() != null) {
                pacientesAtendidos = count(connection, COUNT_PACIENTES_DE_MEDICO, null, null, safe.idMedico());
            }
            long citasTotales = 0;
            long recetasTotales = 0;
            long historiasTotales = 0;
            if (safe.idPaciente() != null) {
                long id = safe.idPaciente();
                citasTotales = count(connection, COUNT_CITAS_DE_PACIENTE, null, null, id);
                recetasTotales = count(connection, COUNT_RECETAS_DE_PACIENTE, null, null, id);
                historiasTotales = count(connection, COUNT_HISTORIAS_DE_PACIENTE, null, null, id);
            }
            return new DashboardSnapshot(citasHoy, citasPendientes, resumen, duracion, proximas,
                medicosActivos, pacientesActivos, medicamentosActivos, pacientesAtendidos,
                citasTotales, recetasTotales, historiasTotales);
        });
    }

    /** Binds the non-null bounds in order and then the scope id, if any. */
    private static long count(Connection connection, String sql, OffsetDateTime from, OffsetDateTime to, Long scopeId)
        throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = 1;
            if (from != null) {
                ps.setObject(index++, from);
            }
            if (to != null) {
                ps.setObject(index++, to);
            }
            if (scopeId != null) {
                ps.setLong(index, scopeId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private static EstadoCounts resumen(Connection connection, Scope scope, OffsetDateTime dayStart,
                                        OffsetDateTime dayEnd, Long scopeId, String countScope) throws SQLException {
        long total = 0;
        long atendidas = 0;
        long enCurso = 0;
        long pendientes = 0;
        long canceladas = 0;
        try (PreparedStatement ps = connection.prepareStatement(RESUMEN_DEL_DIA + countScope + RESUMEN_GROUP_BY)) {
            ps.setObject(1, dayStart);
            ps.setObject(2, dayEnd);
            if (scopeId != null) {
                ps.setLong(3, scopeId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    EstadoCita estado = EstadoCita.valueOf(rs.getString(1));
                    long value = rs.getLong(2);
                    total += value;
                    switch (estado) {
                        case FINALIZADA -> atendidas += value;
                        case EN_CURSO -> enCurso += value;
                        case PROGRAMADA -> pendientes += value;
                        case CANCELADA -> canceladas += value;
                    }
                }
            }
        }
        return new EstadoCounts(total, atendidas, enCurso, pendientes, canceladas);
    }

    private static Double duracion(Connection connection, OffsetDateTime dayStart, OffsetDateTime dayEnd,
                                   Long scopeId, String countScope) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(DURACION_PROMEDIO + countScope)) {
            ps.setObject(1, dayStart);
            ps.setObject(2, dayEnd);
            if (scopeId != null) {
                ps.setLong(3, scopeId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                double value = rs.getDouble(1);
                return rs.wasNull() ? null : value;
            }
        }
    }

    private static List<CitaView> proximas(Connection connection, OffsetDateTime now, Long scopeId,
                                           String joinedScope, int limit) throws SQLException {
        List<CitaView> items = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(PROXIMAS_CITAS + joinedScope + PROXIMAS_ORDER)) {
            int index = 1;
            ps.setObject(index++, now);
            if (scopeId != null) {
                ps.setLong(index++, scopeId);
            }
            ps.setInt(index, safeLimit(limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(CitaDao.map(rs));
                }
            }
        }
        return items;
    }
}
