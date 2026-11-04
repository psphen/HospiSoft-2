package com.hospisoft.dao;

import com.hospisoft.db.ConnectionPool;
import com.hospisoft.model.AccionAuditoria;
import com.hospisoft.model.Auditoria;
import com.hospisoft.model.ModuloAuditoria;
import com.hospisoft.model.Page;
import com.hospisoft.model.Rol;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC implementation of {@link AuditoriaRepository}. PreparedStatement only: the WHERE clause is
 * assembled from constant fragments and every user value travels as a parameter. {@code fecha} is
 * never bound on insert (the column default {@code now()} is the authoritative timestamp) and is
 * read as {@link OffsetDateTime} (TIMESTAMPTZ).
 */
public final class AuditoriaDao implements AuditoriaRepository {

    static final String COLUMNS =
        "a.id, a.fecha, a.id_usuario, a.username, a.rol, a.accion, a.modulo, a.id_registro, a.detalle";

    static final String SELECT = "SELECT " + COLUMNS + " FROM auditoria a";

    static final String COUNT = "SELECT count(*) FROM auditoria a";

    static final String INSERT = """
        INSERT INTO auditoria (id_usuario, username, rol, accion, modulo, id_registro, detalle)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

    static final String TEXT_CLAUSE = "(a.username ILIKE ? ESCAPE '\\' OR a.detalle ILIKE ? ESCAPE '\\')";

    static final String ORDER = " ORDER BY a.fecha DESC, a.id DESC";

    static final String PAGING = " LIMIT ? OFFSET ?";

    private final ConnectionPool pool;

    public AuditoriaDao(ConnectionPool pool) {
        this.pool = pool;
    }

    @Override
    public void insert(Auditoria entry) {
        pool.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
                setNullableLong(ps, 1, entry.idUsuario());
                DaoSupport.setNullable(ps, 2, entry.username());
                DaoSupport.setNullable(ps, 3, entry.rol() == null ? null : entry.rol().name());
                ps.setString(4, entry.accion().name());
                ps.setString(5, entry.modulo().name());
                setNullableLong(ps, 6, entry.idRegistro());
                DaoSupport.setNullable(ps, 7, entry.detalle());
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public Page<Auditoria> findPage(AuditoriaQuery query, int page, int pageSize) {
        AuditoriaQuery q = query == null ? AuditoriaQuery.NONE : query;
        long offset = Filters.offset(page, pageSize);
        return pool.withConnection(connection -> {
            List<String> clauses = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            if (q.texto() != null) {
                clauses.add(TEXT_CLAUSE);
                String pattern = Filters.likePattern(q.texto());
                params.add(pattern);
                params.add(pattern);
            }
            if (q.accion() != null) {
                clauses.add("a.accion = ?");
                params.add(q.accion().name());
            }
            if (q.modulo() != null) {
                clauses.add("a.modulo = ?");
                params.add(q.modulo().name());
            }
            if (q.from() != null) {
                clauses.add("a.fecha >= ?");
                params.add(q.from());
            }
            if (q.to() != null) {
                clauses.add("a.fecha < ?");
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
            List<Auditoria> items = new ArrayList<>();
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

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static Auditoria map(ResultSet rs) throws SQLException {
        String rol = rs.getString("rol");
        return new Auditoria(
            rs.getLong("id"),
            rs.getObject("fecha", OffsetDateTime.class),
            rs.getObject("id_usuario", Long.class),
            rs.getString("username"),
            rol == null ? null : Rol.valueOf(rol),
            AccionAuditoria.valueOf(rs.getString("accion")),
            ModuloAuditoria.valueOf(rs.getString("modulo")),
            rs.getObject("id_registro", Long.class),
            rs.getString("detalle"));
    }
}
