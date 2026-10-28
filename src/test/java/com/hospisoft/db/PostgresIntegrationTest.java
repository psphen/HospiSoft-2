package com.hospisoft.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.dao.AtencionDao;
import com.hospisoft.dao.DashboardDao;
import com.hospisoft.dao.DashboardRepository;
import com.hospisoft.dao.AtencionRepository;
import com.hospisoft.dao.HistoriaQuery;
import com.hospisoft.dao.MedicoDao;
import com.hospisoft.dao.MedicoFilter;
import com.hospisoft.model.Medico;
import com.hospisoft.model.HistoriaView;
import com.hospisoft.model.Page;
import com.hospisoft.service.AppZone;
import com.hospisoft.service.CitaService;
import com.hospisoft.service.ConflictException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in integration test against a real PostgreSQL (Supabase session pooler). Enabled only when
 * HOSPISOFT_TEST_DB_URL, HOSPISOFT_TEST_DB_USER and HOSPISOFT_TEST_DB_PASSWORD are set; otherwise
 * skipped. Every test runs inside a transaction that is rolled back, so no data is left behind.
 * Requires db/schema.sql to have been applied.
 */
@EnabledIfEnvironmentVariable(named = "HOSPISOFT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "HOSPISOFT_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "HOSPISOFT_TEST_DB_PASSWORD", matches = ".+")
class PostgresIntegrationTest {

    private Connection connection;

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(System.getenv("HOSPISOFT_TEST_DB_URL"),
            System.getenv("HOSPISOFT_TEST_DB_USER"), System.getenv("HOSPISOFT_TEST_DB_PASSWORD"));
    }

    @BeforeEach
    void begin() throws SQLException {
        connection = open();
        connection.setAutoCommit(false);
    }

    /**
     * Rolls back the statement-scoped transaction and then removes the rows the DAO tests committed
     * through the pool. The immutability triggers are disabled only for that cleanup and restored
     * right after, so the test never leaves data behind.
     */
    @AfterEach
    void cleanUp() throws SQLException {
        try {
            connection.rollback();
        } finally {
            connection.close();
        }
        if (committed.isEmpty()) {
            return;
        }
        try (Connection admin = open()) {
            admin.setAutoCommit(false);
            try (Statement st = admin.createStatement()) {
                st.execute("ALTER TABLE receta DISABLE TRIGGER trg_receta_immutable");
                st.execute("ALTER TABLE historia_clinica DISABLE TRIGGER trg_historia_clinica_immutable");
                try {
                    // reverse foreign-key order
                    st.executeUpdate("DELETE FROM receta WHERE id_historia IN "
                        + "(SELECT id FROM historia_clinica WHERE diagnostico LIKE '" + MARKER + "%')");
                    st.executeUpdate("DELETE FROM historia_clinica WHERE diagnostico LIKE '" + MARKER + "%'");
                    st.executeUpdate("DELETE FROM cita WHERE motivo LIKE '" + MARKER + "%'");
                    st.executeUpdate("DELETE FROM medicamento WHERE codigo LIKE 'IT-%'");
                    st.executeUpdate("DELETE FROM medico WHERE documento LIKE 'IT-%'");
                    st.executeUpdate("DELETE FROM paciente WHERE documento LIKE 'IT-%'");
                } finally {
                    st.execute("ALTER TABLE historia_clinica ENABLE TRIGGER trg_historia_clinica_immutable");
                    st.execute("ALTER TABLE receta ENABLE TRIGGER trg_receta_immutable");
                }
            }
            admin.commit();
        }
        committed.clear();
    }

    /** Marks every row a committed (pool-backed) test creates, so cleanUp can find them. */
    private static final String MARKER = "[integration-test]";
    private final List<String> committed = new ArrayList<>();

    private ConnectionPool poolForWrites() {
        committed.add("pool");
        return new ConnectionPool(PostgresIntegrationTest::open, 2, 5_000, System::currentTimeMillis);
    }

    @Test
    void atencionDaoClosesAnAttentionForReal() throws SQLException {
        long medico;
        long paciente;
        long cita;
        long medicamento;
        try (Connection setup = open()) {
            setup.setAutoCommit(true);
            medico = insertMedicoOn(setup);
            paciente = insertPacienteOn(setup);
            medicamento = insertMedicamentoOn(setup, 10);
            cita = insertCitaOn(setup, medico, paciente, "EN_CURSO");
            committed.add("rows");
        }
        try (ConnectionPool pool = poolForWrites()) {
            AtencionDao dao = new AtencionDao(pool);
            long historia = dao.inTransaction(tx -> {
                assertEquals(java.util.Optional.of(true), tx.lockMedico(medico));
                AtencionRepository.MedicamentoLock lock = tx.lockMedicamento(medicamento).orElseThrow();
                assertEquals(10, lock.stock());
                assertEquals(1, tx.finalizar(cita, medico, MARKER + " cerrada"));
                long id = tx.insertHistoria(cita, paciente, medico, MARKER + " dx", "tx", null);
                tx.insertReceta(id, medicamento, 4, "1 c/8h", "3 dias", true, null);
                assertEquals(1, tx.decrementStock(medicamento, 4));
                return id;
            });

            // the joined reads see the committed rows
            HistoriaView stored = dao.findHistoriaByCita(cita).orElseThrow();
            assertEquals(historia, stored.historia().id());
            assertEquals(1, stored.recetas().size());
            assertEquals(4, stored.recetas().get(0).receta().cantidad());
            assertEquals(1, dao.findRecetasByHistoria(historia).size());
            Page<HistoriaView> page = dao.findHistoriasPage(new HistoriaQuery(paciente, medico, "dx"), 1, 10);
            assertEquals(1, page.total());
            assertEquals(1, dao.findRecetasPage(paciente, 1, 10).total());

            // a second close finds the cita FINALIZADA and the historia already there
            assertEquals(0, (int) dao.inTransaction(tx -> tx.finalizar(cita, medico, "again")));
            ConflictException duplicate = assertThrows(ConflictException.class, () -> dao.inTransaction(tx ->
                tx.insertHistoria(cita, paciente, medico, MARKER + " dup", null, null)));
            assertEquals(AtencionDao.ALREADY_CLOSED, duplicate.userMessage());

            // the stock guard refuses to go below zero on the real column
            assertEquals(0, (int) dao.inTransaction(tx -> tx.decrementStock(medicamento, 999)));
            assertEquals(6, stockOfOn(medicamento));
        }
    }

    private int stockOfOn(long idMedicamento) throws SQLException {
        try (Connection read = open();
             PreparedStatement ps = read.prepareStatement("SELECT stock FROM medicamento WHERE id = ?")) {
            ps.setLong(1, idMedicamento);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static long insertMedicoOn(Connection c) throws SQLException {
        return insertReturningId(c, "INSERT INTO medico (documento, nombres, apellidos, especialidad, activo)"
            + " VALUES ('IT-" + System.nanoTime() + "', 'Test', 'Integration', 'Test', TRUE) RETURNING id");
    }

    private static long insertPacienteOn(Connection c) throws SQLException {
        return insertReturningId(c, "INSERT INTO paciente (documento, nombres, apellidos, fecha_nacimiento, sexo, activo)"
            + " VALUES ('IT-" + System.nanoTime() + "', 'Test', 'Integration', DATE '1990-01-01', 'OTRO', TRUE)"
            + " RETURNING id");
    }

    private static long insertMedicamentoOn(Connection c, int stock) throws SQLException {
        return insertReturningId(c, "INSERT INTO medicamento (codigo, nombre, presentacion, stock, stock_minimo, activo)"
            + " VALUES ('IT-" + System.nanoTime() + "', 'Integration', 'Caja', " + stock + ", 1, TRUE) RETURNING id");
    }

    private static long insertCitaOn(Connection c, long medico, long paciente, String estado) throws SQLException {
        return insertReturningId(c, "INSERT INTO cita (id_medico, id_paciente, fecha_hora_programada, motivo, estado,"
            + " hora_inicio_real) VALUES (" + medico + ", " + paciente + ", now() + interval '90 days', '"
            + MARKER + " cita', '" + estado + "', now()) RETURNING id");
    }

    private static long insertReturningId(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static ConnectionPool readOnlyPool() {
        return new ConnectionPool(PostgresIntegrationTest::open, 1, 5_000, System::currentTimeMillis);
    }

    @Test
    void theSevenTablesExist() {
        try (ConnectionPool pool = readOnlyPool()) {
            assertEquals(List.of(), new SchemaChecker(pool).findMissing());
        }
    }

    /**
     * The dashboard is the only screen whose SQL is composed at runtime (scope fragments, GROUP BY,
     * EXTRACT and AVG), so every shape it can take is executed here against a real PostgreSQL.
     */
    @Test
    void theDashboardSnapshotRunsForEveryScope() {
        try (ConnectionPool pool = readOnlyPool()) {
            DashboardDao dao = new DashboardDao(pool);
            AppZone zone = AppZone.systemDefault();
            LocalDate today = LocalDate.now(zone.zone());
            OffsetDateTime dayStart = zone.dayStart(today);
            OffsetDateTime dayEnd = zone.dayEnd(today);
            OffsetDateTime now = OffsetDateTime.now();

            for (DashboardRepository.Scope scope : List.of(DashboardRepository.Scope.ALL,
                DashboardRepository.Scope.medico(insertMedico()),
                DashboardRepository.Scope.paciente(insertPaciente()))) {
                var snapshot = dao.snapshot(scope, dayStart, dayEnd, now, 5);
                assertTrue(snapshot.citasHoy() >= 0);
                assertTrue(snapshot.resumenHoy().total() >= snapshot.resumenHoy().noCanceladas());
                assertTrue(snapshot.proximasCitas().size() <= 5);
            }
        } catch (SQLException ex) {
            throw new AssertionError("could not prepare the dashboard scopes", ex);
        }
    }

    @Test
    void medicoPageWithEveryFilterRuns() {
        try (ConnectionPool pool = readOnlyPool()) {
            MedicoDao dao = new MedicoDao(pool);
            Page<Medico> page = dao.findPage(new MedicoFilter("Medicina General", true, "gar%"), 1, 10);
            assertTrue(page.total() >= 0);
            assertFalse(dao.findEspecialidades().contains(null));
            assertTrue(dao.findActiveForSelect().size() >= 0);
        }
    }

    @Test
    void lockStatementsAndIntervalArithmeticExecute() throws SQLException {
        long medico = insertMedico();
        long paciente = insertPaciente();
        try (PreparedStatement ps = connection.prepareStatement("SELECT activo FROM medico WHERE id = ? FOR NO KEY UPDATE")) {
            ps.setLong(1, medico);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next() && rs.getBoolean(1));
            }
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT activo FROM paciente WHERE id = ? FOR NO KEY UPDATE")) {
            ps.setLong(1, paciente);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next() && rs.getBoolean(1));
            }
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT now() + make_interval(mins => ?) > now()")) {
            ps.setInt(1, CitaService.SLOT_MINUTES);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next() && rs.getBoolean(1));
            }
        }
    }

    @Test
    void overlapPredicateBoundaries() throws SQLException {
        long medico = insertMedico();
        long paciente = insertPaciente();
        OffsetDateTime start = OffsetDateTime.now().truncatedTo(ChronoUnit.HOURS).plusDays(30);
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO cita (id_medico, id_paciente, fecha_hora_programada, motivo, estado) VALUES (?, ?, ?, ?, 'PROGRAMADA')")) {
            ps.setLong(1, medico);
            ps.setLong(2, paciente);
            ps.setObject(3, start);
            ps.setString(4, "[integration-test] temp");
            ps.executeUpdate();
        }
        assertFalse(overlaps(medico, start.plusMinutes(30)), "adjacent after");
        assertFalse(overlaps(medico, start.minusMinutes(30)), "adjacent before");
        assertTrue(overlaps(medico, start), "same start");
        assertTrue(overlaps(medico, start.plusMinutes(15)), "partial overlap after");
        assertTrue(overlaps(medico, start.minusMinutes(15)), "partial overlap before");
    }

    private boolean overlaps(long medico, OffsetDateTime newStart) throws SQLException {
        String sql = "SELECT EXISTS (SELECT 1 FROM cita WHERE id_medico = ? AND estado <> 'CANCELADA' AND id <> ?"
            + " AND fecha_hora_programada < ? AND fecha_hora_programada + make_interval(mins => ?) > ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, medico);
            ps.setLong(2, -1L);
            ps.setObject(3, newStart.plusMinutes(CitaService.SLOT_MINUTES));
            ps.setInt(4, CitaService.SLOT_MINUTES);
            ps.setObject(5, newStart);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    @Test
    void historiaClinicaIsImmutable() throws SQLException {
        long medico = insertMedico();
        long paciente = insertPaciente();
        long cita = insertCita(medico, paciente);
        long historia;
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO historia_clinica (id_cita, id_paciente, id_medico, diagnostico, tratamiento)"
                + " VALUES (?, ?, ?, '[integration-test] dx', 'tx') RETURNING id")) {
            ps.setLong(1, cita);
            ps.setLong(2, paciente);
            ps.setLong(3, medico);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                historia = rs.getLong(1);
            }
        }
        // the UPDATE/DELETE triggers must raise; the transaction needs a savepoint to continue
        Savepoint savepoint = connection.setSavepoint();
        SQLException update = assertThrows(SQLException.class, () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE historia_clinica SET diagnostico = 'changed' WHERE id = ?")) {
                ps.setLong(1, historia);
                ps.executeUpdate();
            }
        });
        assertTrue(update.getMessage().toLowerCase().contains("immutable"), update.getMessage());
        connection.rollback(savepoint);

        Savepoint second = connection.setSavepoint();
        SQLException delete = assertThrows(SQLException.class, () -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM historia_clinica WHERE id = ?")) {
                ps.setLong(1, historia);
                ps.executeUpdate();
            }
        });
        assertTrue(delete.getMessage().toLowerCase().contains("immutable"), delete.getMessage());
        connection.rollback(second);

        // a second historia for the same cita violates the UNIQUE constraint on id_cita
        Savepoint third = connection.setSavepoint();
        SQLException duplicate = assertThrows(SQLException.class, () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO historia_clinica (id_cita, id_paciente, id_medico, diagnostico)"
                    + " VALUES (?, ?, ?, 'dup')")) {
                ps.setLong(1, cita);
                ps.setLong(2, paciente);
                ps.setLong(3, medico);
                ps.executeUpdate();
            }
        });
        assertEquals("23505", duplicate.getSQLState());
        connection.rollback(third);
    }

    @Test
    void recetaIsImmutable() throws SQLException {
        long medico = insertMedico();
        long paciente = insertPaciente();
        long cita = insertCita(medico, paciente);
        long medicamento = insertMedicamento(10);
        long historia;
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO historia_clinica (id_cita, id_paciente, id_medico, diagnostico)"
                + " VALUES (?, ?, ?, '[integration-test] dx') RETURNING id")) {
            ps.setLong(1, cita);
            ps.setLong(2, paciente);
            ps.setLong(3, medico);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                historia = rs.getLong(1);
            }
        }
        long receta;
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO receta (id_historia, id_medicamento, cantidad, dosis, duracion, entregado)"
                + " VALUES (?, ?, 1, 'd', 'x', TRUE) RETURNING id")) {
            ps.setLong(1, historia);
            ps.setLong(2, medicamento);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                receta = rs.getLong(1);
            }
        }
        Savepoint beforeUpdate = connection.setSavepoint();
        SQLException update = assertThrows(SQLException.class, () -> {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE receta SET cantidad = 2 WHERE id = ?")) {
                ps.setLong(1, receta);
                ps.executeUpdate();
            }
        });
        assertTrue(update.getMessage().toLowerCase().contains("immutable"), update.getMessage());
        connection.rollback(beforeUpdate);

        Savepoint beforeDelete = connection.setSavepoint();
        SQLException delete = assertThrows(SQLException.class, () -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM receta WHERE id = ?")) {
                ps.setLong(1, receta);
                ps.executeUpdate();
            }
        });
        assertTrue(delete.getMessage().toLowerCase().contains("immutable"), delete.getMessage());
        connection.rollback(beforeDelete);
    }

    @Test
    void stockDecrementGuardRefusesToGoNegative() throws SQLException {
        long medicamento = insertMedicamento(5);
        assertEquals(0, decrement(medicamento, 10), "the guard must refuse an oversized decrement");
        assertEquals(5, stockOf(medicamento));
        assertEquals(1, decrement(medicamento, 5));
        assertEquals(0, stockOf(medicamento));
    }

    private int decrement(long idMedicamento, int cantidad) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE medicamento SET stock = stock - ? WHERE id = ? AND stock >= ?")) {
            ps.setInt(1, cantidad);
            ps.setLong(2, idMedicamento);
            ps.setInt(3, cantidad);
            return ps.executeUpdate();
        }
    }

    private int stockOf(long idMedicamento) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT stock FROM medicamento WHERE id = ?")) {
            ps.setLong(1, idMedicamento);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private long insertMedicamento(int stock) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO medicamento (codigo, nombre, presentacion, stock, stock_minimo, activo)"
                + " VALUES (?, 'Integration', 'Caja', ?, 1, TRUE) RETURNING id")) {
            ps.setString(1, "IT-" + System.nanoTime());
            ps.setInt(2, stock);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertCita(long medico, long paciente) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO cita (id_medico, id_paciente, fecha_hora_programada, motivo, estado)"
                + " VALUES (?, ?, now() + interval '60 days', '[integration-test] cita', 'PROGRAMADA') RETURNING id")) {
            ps.setLong(1, medico);
            ps.setLong(2, paciente);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertMedico() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO medico (documento, nombres, apellidos, especialidad, activo) VALUES (?, 'Test', 'Integration', 'Test', TRUE) RETURNING id")) {
            ps.setString(1, "IT-" + System.nanoTime());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertPaciente() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO paciente (documento, nombres, apellidos, fecha_nacimiento, sexo, activo) VALUES (?, 'Test', 'Integration', DATE '1990-01-01', 'OTRO', TRUE) RETURNING id")) {
            ps.setString(1, "IT-" + System.nanoTime());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
