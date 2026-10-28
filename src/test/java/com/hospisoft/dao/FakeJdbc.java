package com.hospisoft.dao;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Minimal JDBC recorder for DAO tests: records every prepared SQL string with its bound parameters
 * (in index order) and answers each execute with the next canned outcome: rows for a query, an
 * affected-row count for an update, or an SQLException to throw.
 */
public final class FakeJdbc {

    /** One prepared statement: its SQL, the parameters bound by index and the indexes set to NULL. */
    public static final class Call {
        public final String sql;
        public final Map<Integer, Object> params = new TreeMap<>();
        public final TreeSet<Integer> nulls = new TreeSet<>();

        Call(String sql) {
            this.sql = sql;
        }

        /** Bound values in index order (NULLs appear as null). */
        public List<Object> values() {
            return new ArrayList<>(params.values());
        }
    }

    public final List<Call> calls = new ArrayList<>();
    /** Connection lifecycle events: setAutoCommit(false|true), commit, rollback. */
    public final List<String> events = new ArrayList<>();
    /** Optional failures injected into the lifecycle methods (consumed once). */
    public SQLException failCommit;
    public SQLException failRollback;
    public SQLException failSetAutoCommitFalse;
    public SQLException failSetAutoCommitTrue;
    private final Deque<Object> outcomes = new ArrayDeque<>();

    /** Canned query result: each row is a column-name -> value map in column order. */
    @SafeVarargs
    public final FakeJdbc rows(Map<String, Object>... rows) {
        outcomes.add(List.of(rows));
        return this;
    }

    public FakeJdbc update(int affected) {
        outcomes.add(affected);
        return this;
    }

    public FakeJdbc fail(String sqlState, String message) {
        outcomes.add(new SQLException(message, sqlState));
        return this;
    }

    public Call call(int index) {
        return calls.get(index);
    }

    public Call last() {
        return calls.get(calls.size() - 1);
    }

    public static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    private Object nextOutcome() throws SQLException {
        Object outcome = outcomes.pollFirst();
        if (outcome == null) {
            throw new IllegalStateException("No canned outcome left for " + last().sql);
        }
        if (outcome instanceof SQLException ex) {
            throw ex;
        }
        return outcome;
    }

    public Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "prepareStatement": return statement(new Call((String) args[0]));
                    case "setAutoCommit": {
                        events.add("setAutoCommit(" + args[0] + ")");
                        boolean on = (Boolean) args[0];
                        SQLException failure = on ? failSetAutoCommitTrue : failSetAutoCommitFalse;
                        if (failure != null) {
                            if (on) {
                                failSetAutoCommitTrue = null;
                            } else {
                                failSetAutoCommitFalse = null;
                            }
                            throw failure;
                        }
                        return null;
                    }
                    case "commit": {
                        events.add("commit");
                        if (failCommit != null) {
                            SQLException failure = failCommit;
                            failCommit = null;
                            throw failure;
                        }
                        return null;
                    }
                    case "rollback": {
                        events.add("rollback");
                        if (failRollback != null) {
                            SQLException failure = failRollback;
                            failRollback = null;
                            throw failure;
                        }
                        return null;
                    }
                    case "isValid": return true;
                    case "isClosed": return false;
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "toString": return "FakeConnection";
                    default: return null; // close, setAutoCommit, ...
                }
            });
    }

    private PreparedStatement statement(Call call) {
        calls.add(call);
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
            new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                String name = method.getName();
                if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index) {
                    if (name.equals("setNull")) {
                        call.nulls.add(index);
                        call.params.put(index, null);
                    } else {
                        call.params.put(index, args[1]);
                    }
                    return null;
                }
                switch (name) {
                    case "executeQuery": {
                        Object outcome = nextOutcome();
                        if (!(outcome instanceof List)) {
                            throw new IllegalStateException("executeQuery got a non-row outcome for " + call.sql);
                        }
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> rows = (List<Map<String, Object>>) outcome;
                        return resultSet(rows);
                    }
                    case "executeUpdate": {
                        Object outcome = nextOutcome();
                        if (!(outcome instanceof Integer)) {
                            throw new IllegalStateException("executeUpdate got a non-count outcome for " + call.sql);
                        }
                        return outcome;
                    }
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "toString": return "FakeStatement(" + call.sql + ")";
                    default: return null; // close, ...
                }
            });
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        int[] cursor = {-1};
        boolean[] wasNull = {false};
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
                String name = method.getName();
                switch (name) {
                    case "next":
                        cursor[0]++;
                        return cursor[0] < rows.size();
                    case "wasNull":
                        return wasNull[0];
                    case "close":
                        return null;
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "toString": return "FakeResultSet";
                    default:
                        break;
                }
                if (!name.startsWith("get")) {
                    return null;
                }
                Map<String, Object> row = rows.get(cursor[0]);
                Object value = args[0] instanceof Integer index
                    ? new ArrayList<>(row.values()).get(index - 1)
                    : row.get((String) args[0]);
                wasNull[0] = value == null;
                switch (name) {
                    case "getLong": return value == null ? 0L : ((Number) value).longValue();
                    case "getInt": return value == null ? 0 : ((Number) value).intValue();
                    case "getDouble": return value == null ? 0.0 : ((Number) value).doubleValue();
                    case "getBoolean": return value != null && (Boolean) value;
                    case "getString": return value == null ? null : String.valueOf(value);
                    case "getObject": return value;
                    default: throw new UnsupportedOperationException(name);
                }
            });
    }
}
