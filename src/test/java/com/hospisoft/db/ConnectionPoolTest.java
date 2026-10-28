package com.hospisoft.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ConnectionPoolTest {

    /** Fake connection: only the methods the pool touches are meaningful. */
    static final class FakeConnection {
        final AtomicBoolean valid = new AtomicBoolean(true);
        final AtomicBoolean closed = new AtomicBoolean(false);
        boolean failAutoCommit;
        final Connection proxy;

        FakeConnection() {
            proxy = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (p, method, args) -> {
                    switch (method.getName()) {
                        case "isValid": return valid.get() && !closed.get();
                        case "isClosed": return closed.get();
                        case "close": closed.set(true); return null;
                        case "setAutoCommit":
                            if (failAutoCommit) {
                                throw new SQLException("autocommit failed", "08006");
                            }
                            return null;
                        case "hashCode": return System.identityHashCode(p);
                        case "equals": return p == args[0];
                        case "toString": return "FakeConnection";
                        default: return null;
                    }
                });
        }
    }

    static final class FakeFactory implements ConnectionPool.ConnectionFactory {
        final List<FakeConnection> opened = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        SQLException nextFailure;
        boolean nextFailsAutoCommit;

        @Override
        public Connection open() throws SQLException {
            calls.incrementAndGet();
            if (nextFailure != null) {
                SQLException ex = nextFailure;
                nextFailure = null;
                throw ex;
            }
            FakeConnection fake = new FakeConnection();
            fake.failAutoCommit = nextFailsAutoCommit;
            nextFailsAutoCommit = false;
            opened.add(fake);
            return fake.proxy;
        }
    }

    private final FakeFactory factory = new FakeFactory();
    private final AtomicLong now = new AtomicLong(1_000_000);
    /** Milliseconds the fake clock advances on every read; non-zero makes borrow deadlines expire. */
    private final AtomicLong tick = new AtomicLong(0);

    private ConnectionPool pool(int size, long waitMillis) {
        return new ConnectionPool(factory, size, waitMillis, () -> now.addAndGet(tick.get()));
    }

    @Test
    void poolIsBoundedAndExhaustionTimesOut() {
        ConnectionPool pool = pool(2, 50);
        Connection a = pool.borrow();
        Connection b = pool.borrow();
        assertNotSame(a, b);
        assertEquals(2, pool.inUse());
        tick.set(30); // each clock read moves time forward so the 50 ms deadline expires
        assertThrows(DatabaseUnavailableException.class, pool::borrow);
        tick.set(0);
        assertEquals(2, factory.calls.get());
        pool.release(a);
        assertSame(a, pool.borrow());
        assertEquals(2, factory.calls.get());
    }

    @Test
    void deadIdleConnectionIsDiscardedAndReplaced() {
        ConnectionPool pool = pool(1, 50);
        Connection first = pool.borrow();
        pool.release(first);
        factory.opened.get(0).valid.set(false);
        Connection second = pool.borrow();
        assertNotSame(first, second);
        assertTrue(factory.opened.get(0).closed.get());
        assertEquals(2, factory.calls.get());
        assertEquals(1, pool.inUse());
    }

    @Test
    void connectionIsReleasedWhenTheFunctionThrowsANonConnectionError() {
        ConnectionPool pool = pool(1, 50);
        assertThrows(IllegalStateException.class, () -> pool.withConnection(c -> {
            throw new SQLException("relation does not exist", "42P01");
        }));
        assertEquals(0, pool.inUse());
        assertEquals(1, pool.idleCount());
        pool.withConnection(c -> 1); // reuses the same connection
        assertEquals(1, factory.calls.get());
    }

    @Test
    void connectionErrorInsideTheFunctionDiscardsTheConnection() {
        ConnectionPool pool = pool(1, 50);
        assertThrows(DatabaseUnavailableException.class, () -> pool.withConnection(c -> {
            throw new SQLException("terminated", "57P01");
        }));
        assertEquals(0, pool.inUse());
        assertEquals(0, pool.idleCount());
        assertTrue(factory.opened.get(0).closed.get());
    }

    @Test
    void connectFailureIsUnavailableAndSlotIsGivenBack() {
        ConnectionPool pool = pool(1, 50);
        factory.nextFailure = new SQLException("refused", "08001");
        assertThrows(DatabaseUnavailableException.class, pool::borrow);
        assertEquals(0, pool.inUse());
    }

    @Test
    void failFastWindowSkipsTheFactoryForThreeSeconds() {
        ConnectionPool pool = pool(1, 50);
        factory.nextFailure = new SQLException("refused", "08001");
        assertThrows(DatabaseUnavailableException.class, pool::borrow);
        assertEquals(1, factory.calls.get());

        now.addAndGet(1_000);
        assertThrows(DatabaseUnavailableException.class, pool::borrow);
        assertEquals(1, factory.calls.get(), "factory must not be called inside the fail-fast window");

        now.addAndGet(ConnectionPool.FAIL_FAST_MILLIS);
        Connection recovered = pool.borrow();
        assertEquals(2, factory.calls.get());
        assertEquals(1, pool.inUse());
        pool.release(recovered);
    }

    @Test
    void badCredentialsAreReportedAsAuthFailure() {
        ConnectionPool pool = pool(1, 50);
        factory.nextFailure = new SQLException("password authentication failed", "28P01");
        assertThrows(DatabaseAuthException.class, pool::borrow);
        assertEquals(0, pool.inUse());
    }

    @Test
    void autoCommitFailureClosesTheConnection() {
        ConnectionPool pool = pool(1, 50);
        factory.nextFailsAutoCommit = true;
        assertThrows(DatabaseUnavailableException.class, pool::borrow);
        assertTrue(factory.opened.get(0).closed.get());
        assertEquals(0, pool.inUse());
    }

    @Test
    void doubleReleaseIsIgnored() {
        ConnectionPool pool = pool(2, 50);
        Connection a = pool.borrow();
        pool.release(a);
        pool.release(a);
        pool.release(factory.opened.get(0).proxy);
        assertEquals(0, pool.inUse());
        assertEquals(1, pool.idleCount());
        Connection b = pool.borrow();
        Connection c = pool.borrow();
        assertEquals(2, pool.inUse());
        tick.set(30);
        assertThrows(DatabaseUnavailableException.class, pool::borrow);
        tick.set(0);
        pool.release(b);
        pool.release(c);
    }

    @Test
    void typedRuntimeExceptionsPassThroughUnchangedAndReleaseTheConnection() {
        ConnectionPool pool = pool(1, 50);
        IllegalArgumentException typed = new IllegalArgumentException("typed");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> pool.withConnection(c -> {
            throw typed;
        }));
        assertSame(typed, thrown);
        assertEquals(0, pool.inUse());
        assertEquals(1, pool.idleCount());
    }

    @Test
    void inTransactionCommitsOnSuccessAndRestoresAutocommit() {
        com.hospisoft.dao.FakeJdbc jdbc = new com.hospisoft.dao.FakeJdbc();
        ConnectionPool pool = new ConnectionPool(jdbc::connection, 1, 50, now::get);
        jdbc.update(1);
        Integer result = pool.inTransaction(c -> {
            try (java.sql.PreparedStatement ps = c.prepareStatement("UPDATE x SET y = 1")) {
                return ps.executeUpdate();
            }
        });
        assertEquals(1, result);
        // a fresh connection is first put in autocommit mode by the pool
        assertEquals(List.of("setAutoCommit(true)", "setAutoCommit(false)", "commit", "setAutoCommit(true)"), jdbc.events);
        assertEquals(0, pool.inUse());
        assertEquals(1, pool.idleCount());
    }

    @Test
    void inTransactionRollsBackOnAnyExceptionAndRestoresAutocommit() {
        com.hospisoft.dao.FakeJdbc jdbc = new com.hospisoft.dao.FakeJdbc();
        ConnectionPool pool = new ConnectionPool(jdbc::connection, 1, 50, now::get);
        IllegalArgumentException typed = new IllegalArgumentException("business rule");
        assertSame(typed, assertThrows(IllegalArgumentException.class, () -> pool.inTransaction(c -> {
            throw typed;
        })));
        assertEquals(List.of("setAutoCommit(true)", "setAutoCommit(false)", "rollback", "setAutoCommit(true)"), jdbc.events);
        assertEquals(1, pool.idleCount());

        jdbc.events.clear();
        jdbc.fail("23505", "duplicate");
        assertThrows(IllegalStateException.class, () -> pool.inTransaction(c -> {
            try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT")) {
                return ps.executeUpdate();
            }
        }));
        assertEquals(List.of("setAutoCommit(false)", "rollback", "setAutoCommit(true)"), jdbc.events);

        jdbc.events.clear();
        jdbc.fail("08006", "gone");
        assertThrows(DatabaseUnavailableException.class, () -> pool.inTransaction(c -> {
            try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT")) {
                return ps.executeUpdate();
            }
        }));
        assertEquals(List.of("setAutoCommit(false)", "rollback", "setAutoCommit(true)"), jdbc.events);
        assertEquals(0, pool.inUse());
    }

    @Test
    void inTransactionDiscardsTheConnectionWhenRollbackFails() {
        com.hospisoft.dao.FakeJdbc jdbc = new com.hospisoft.dao.FakeJdbc();
        ConnectionPool pool = new ConnectionPool(jdbc::connection, 1, 50, now::get);
        jdbc.failRollback = new SQLException("rollback failed", "08006");
        assertThrows(IllegalStateException.class, () -> pool.inTransaction(c -> {
            throw new IllegalStateException("business");
        }));
        assertEquals(0, pool.inUse());
        assertEquals(0, pool.idleCount(), "broken connection must be discarded, not released");
        assertTrue(jdbc.events.contains("rollback"));
    }

    @Test
    void inTransactionCommitFailureIsRolledBackAndClassified() {
        com.hospisoft.dao.FakeJdbc jdbc = new com.hospisoft.dao.FakeJdbc();
        ConnectionPool pool = new ConnectionPool(jdbc::connection, 1, 50, now::get);
        jdbc.failCommit = new SQLException("could not serialize", "40001");
        assertThrows(IllegalStateException.class, () -> pool.inTransaction(c -> 1));
        assertEquals(List.of("setAutoCommit(true)", "setAutoCommit(false)", "commit", "rollback", "setAutoCommit(true)"), jdbc.events);
        assertEquals(1, pool.idleCount(), "connection still healthy: released");

        jdbc.events.clear();
        jdbc.failCommit = new SQLException("connection lost", "08006");
        assertThrows(DatabaseUnavailableException.class, () -> pool.inTransaction(c -> 1));
        assertEquals(0, pool.idleCount(), "connection error on commit: discarded");
    }

    @Test
    void inTransactionCannotStartWhenAutocommitOffFails() {
        com.hospisoft.dao.FakeJdbc jdbc = new com.hospisoft.dao.FakeJdbc();
        ConnectionPool pool = new ConnectionPool(jdbc::connection, 1, 50, now::get);
        jdbc.failSetAutoCommitFalse = new SQLException("broken", "08006");
        assertThrows(DatabaseUnavailableException.class, () -> pool.inTransaction(c -> 1));
        assertEquals(0, pool.inUse());
        assertEquals(0, pool.idleCount(), "discarded");
    }

    @Test
    void inTransactionRollsBackErrorsAndRethrowsThem() {
        com.hospisoft.dao.FakeJdbc jdbc = new com.hospisoft.dao.FakeJdbc();
        ConnectionPool pool = new ConnectionPool(jdbc::connection, 1, 50, now::get);
        assertThrows(StackOverflowError.class, () -> pool.inTransaction(c -> {
            throw new StackOverflowError();
        }));
        assertEquals(List.of("setAutoCommit(true)", "setAutoCommit(false)", "rollback", "setAutoCommit(true)"), jdbc.events);
        assertFalse(jdbc.events.contains("commit"));
        assertEquals(1, pool.idleCount(), "released");

        jdbc.events.clear();
        jdbc.failSetAutoCommitTrue = new SQLException("cannot restore", "08006");
        assertEquals(Integer.valueOf(2), pool.inTransaction(c -> 2));
        assertEquals(0, pool.idleCount(), "autocommit restore failed: discarded");
    }

    @Test
    void closedPoolRejectsBorrowAndClosesReleasedConnections() {
        ConnectionPool pool = pool(1, 50);
        Connection a = pool.borrow();
        pool.close();
        assertThrows(DatabaseUnavailableException.class, pool::borrow);
        pool.release(a);
        assertTrue(factory.opened.get(0).closed.get());
    }
}
