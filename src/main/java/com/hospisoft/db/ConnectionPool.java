package com.hospisoft.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Properties;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Minimal bounded JDBC connection pool (no third-party dependency).
 *
 * <p>Design rules:
 * <ul>
 *   <li>The lock only protects bookkeeping. Connecting and validating happen outside it, so a slow
 *       connect never blocks {@link #release} or other borrowers.</li>
 *   <li>A slot is reserved ({@code inUse++}) before connecting and given back if the connect fails.</li>
 *   <li>After a connect failure, borrows within {@link #FAIL_FAST_MILLIS} fail immediately.</li>
 *   <li>Connections are validated before being handed out; dead ones are dropped and replaced.</li>
 *   <li>Double {@link #release} is ignored: {@code inUse} never goes negative.</li>
 * </ul>
 * Connect failures surface as {@link DatabaseUnavailableException}, bad credentials as
 * {@link DatabaseAuthException}.
 */
public final class ConnectionPool implements AutoCloseable {

    /** Work to run with a borrowed connection. */
    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** Opens physical connections. Real: JDBC DriverManager; tests: fakes. */
    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    public static final long FAIL_FAST_MILLIS = 3_000;
    public static final long DEFAULT_BORROW_WAIT_MILLIS = 10_000;
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;
    private static final int CONNECT_TIMEOUT_SECONDS = 5;

    private final ConnectionFactory factory;
    private final int maxSize;
    private final long borrowWaitMillis;
    private final LongSupplier clock;

    private final Object lock = new Object();
    private final Deque<Connection> idle = new ArrayDeque<>();
    private final Set<Connection> borrowed = Collections.newSetFromMap(new IdentityHashMap<>());
    private int inUse;
    private boolean closed;
    private volatile long failFastUntil;

    public ConnectionPool(ConnectionFactory factory, int maxSize, long borrowWaitMillis, LongSupplier clock) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be >= 1");
        }
        this.factory = factory;
        this.maxSize = maxSize;
        this.borrowWaitMillis = borrowWaitMillis;
        this.clock = clock;
    }

    /** Production pool over the PostgreSQL JDBC driver. */
    public static ConnectionPool forJdbc(String url, String user, String password, int maxSize) {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
        properties.setProperty("loginTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
        properties.setProperty("socketTimeout", "30");
        properties.setProperty("ApplicationName", "HospiSoft");
        return new ConnectionPool(() -> DriverManager.getConnection(url, properties), maxSize,
            DEFAULT_BORROW_WAIT_MILLIS, System::currentTimeMillis);
    }

    /**
     * Borrow, run, release. SQLExceptions are classified: connection problems become
     * {@link DatabaseUnavailableException} and the connection is discarded; anything else is an
     * {@link IllegalStateException} (internal error) and the connection goes back to the pool.
     */
    public <T> T withConnection(SqlFunction<T> function) {
        Connection connection = borrow();
        boolean broken = false;
        try {
            return function.apply(connection);
        } catch (SQLException ex) {
            SqlErrorClassifier.Kind kind = SqlErrorClassifier.classify(ex);
            if (kind == SqlErrorClassifier.Kind.UNAVAILABLE || !isAlive(connection)) {
                broken = true;
                throw new DatabaseUnavailableException("Database connection lost: " + ex.getMessage(), ex);
            }
            if (kind == SqlErrorClassifier.Kind.AUTH) {
                broken = true;
                throw new DatabaseAuthException("Database rejected the credentials", ex);
            }
            throw new IllegalStateException("SQL error: " + ex.getMessage(), ex);
        } finally {
            if (broken) {
                discard(connection);
            } else {
                release(connection);
            }
        }
    }

    /**
     * Borrow, run inside a transaction (autocommit off), commit on success, roll back on any
     * exception, always restore autocommit, release. Never call {@link #withConnection} or another
     * {@code inTransaction} from inside {@code function}: a one-connection pool would deadlock.
     */
    public <T> T inTransaction(SqlFunction<T> function) {
        Connection connection = borrow();
        boolean broken = false;
        boolean committed = false;
        try {
            connection.setAutoCommit(false);
        } catch (SQLException ex) {
            discard(connection);
            throw new DatabaseUnavailableException("Cannot start a transaction: " + ex.getMessage(), ex);
        }
        try {
            T result = function.apply(connection);
            connection.commit();
            committed = true;
            return result;
        } catch (SQLException ex) {
            SqlErrorClassifier.Kind kind = SqlErrorClassifier.classify(ex);
            if (kind == SqlErrorClassifier.Kind.UNAVAILABLE || !isAlive(connection)) {
                broken = true;
                throw new DatabaseUnavailableException("Database connection lost: " + ex.getMessage(), ex);
            }
            if (kind == SqlErrorClassifier.Kind.AUTH) {
                broken = true;
                throw new DatabaseAuthException("Database rejected the credentials", ex);
            }
            throw new IllegalStateException("SQL error: " + ex.getMessage(), ex);
        } catch (Throwable ex) {
            throw ex; // business exceptions and Errors: rolled back below, rethrown unchanged
        } finally {
            // pgjdbc commits pending work on setAutoCommit(true): roll back FIRST when not committed
            if (!committed) {
                try {
                    connection.rollback();
                } catch (SQLException | RuntimeException ex) {
                    broken = true;
                }
            }
            try {
                connection.setAutoCommit(true);
            } catch (SQLException | RuntimeException ex) {
                broken = true;
            }
            if (broken) {
                discard(connection);
            } else {
                release(connection);
            }
        }
    }

    public Connection borrow() {
        long deadline = clock.getAsLong() + borrowWaitMillis;
        while (true) {
            Connection candidate;
            synchronized (lock) {
                if (closed) {
                    throw new DatabaseUnavailableException("Connection pool is closed", null);
                }
                candidate = idle.pollFirst();
                if (candidate == null && inUse >= maxSize) {
                    long remaining = deadline - clock.getAsLong();
                    if (remaining <= 0) {
                        throw new DatabaseUnavailableException("Connection pool exhausted", null);
                    }
                    try {
                        lock.wait(Math.max(1, remaining));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new DatabaseUnavailableException("Interrupted while waiting for a connection", ex);
                    }
                    continue;
                }
                inUse++; // slot reserved: for the idle candidate or for a connection to be opened below
            }

            // Validation and connecting happen outside the lock.
            if (candidate != null) {
                if (isAlive(candidate)) {
                    return handOut(candidate);
                }
                closeQuietly(candidate); // dead: the reserved slot is reused for a replacement
            }
            try {
                return handOut(connect());
            } catch (RuntimeException ex) {
                unreserve();
                throw ex;
            }
        }
    }

    public void release(Connection connection) {
        if (connection == null) {
            return;
        }
        synchronized (lock) {
            if (!borrowed.remove(connection)) {
                return; // not borrowed from this pool, or released twice
            }
            inUse--;
            if (closed) {
                closeQuietly(connection);
            } else {
                idle.addLast(connection);
            }
            lock.notifyAll();
        }
    }

    /** Release a connection known to be broken: it is closed instead of returned to the pool. */
    public void discard(Connection connection) {
        if (connection == null) {
            return;
        }
        synchronized (lock) {
            if (!borrowed.remove(connection)) {
                return;
            }
            inUse--;
            lock.notifyAll();
        }
        closeQuietly(connection);
    }

    @Override
    public void close() {
        Deque<Connection> toClose = new ArrayDeque<>();
        synchronized (lock) {
            closed = true;
            toClose.addAll(idle);
            idle.clear();
            lock.notifyAll();
        }
        for (Connection c : toClose) {
            closeQuietly(c);
        }
    }

    /** Package-private, for tests. */
    int inUse() {
        synchronized (lock) {
            return inUse;
        }
    }

    /** Package-private, for tests. */
    int idleCount() {
        synchronized (lock) {
            return idle.size();
        }
    }

    private Connection handOut(Connection connection) {
        synchronized (lock) {
            borrowed.add(connection);
        }
        return connection;
    }

    private void unreserve() {
        synchronized (lock) {
            inUse--;
            lock.notifyAll();
        }
    }

    private Connection connect() {
        long now = clock.getAsLong();
        if (now < failFastUntil) {
            throw new DatabaseUnavailableException("Database unavailable (recent connect failure)", null);
        }
        Connection connection;
        try {
            connection = factory.open();
        } catch (SQLException ex) {
            failFastUntil = clock.getAsLong() + FAIL_FAST_MILLIS;
            if (SqlErrorClassifier.classify(ex) == SqlErrorClassifier.Kind.AUTH) {
                throw new DatabaseAuthException("Database rejected the credentials", ex);
            }
            throw new DatabaseUnavailableException("Cannot connect to the database: " + ex.getMessage(), ex);
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ex) {
            closeQuietly(connection);
            failFastUntil = clock.getAsLong() + FAIL_FAST_MILLIS;
            throw new DatabaseUnavailableException("Cannot initialize the connection: " + ex.getMessage(), ex);
        }
        failFastUntil = 0;
        return connection;
    }

    private static boolean isAlive(Connection connection) {
        try {
            return !connection.isClosed() && connection.isValid(VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException | RuntimeException ex) {
            return false;
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException | RuntimeException ignored) {
            // nothing useful to do with a failing close
        }
    }
}
