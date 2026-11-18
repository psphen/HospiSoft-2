package com.hospisoft.db;

import java.sql.SQLException;

public final class SqlErrorClassifier {

    public enum Kind {
        UNAVAILABLE,
        AUTH,
        OTHER
    }

    private SqlErrorClassifier() {
    }

    public static Kind classify(String sqlState) {
        if (sqlState == null || sqlState.length() < 5) {
            return Kind.OTHER;
        }
        if (sqlState.startsWith("08")) {
            return Kind.UNAVAILABLE;
        }
        if (sqlState.startsWith("57P0")) {
            return Kind.UNAVAILABLE;
        }
        if (sqlState.equals("28000") || sqlState.equals("28P01")) {
            return Kind.AUTH;
        }
        return Kind.OTHER;
    }

    public static Kind classify(SQLException ex) {
        Throwable current = ex;
        boolean sawNullState = false;
        while (current != null) {
            if (current instanceof SQLException sql) {
                Kind kind = classify(sql.getSQLState());
                if (kind != Kind.OTHER) {
                    return kind;
                }
                if (sql.getSQLState() == null) {
                    sawNullState = true;
                }
            }
            if (current instanceof java.net.SocketException || current instanceof java.net.UnknownHostException
                || current instanceof java.net.SocketTimeoutException) {
                return Kind.UNAVAILABLE;
            }
            current = current.getCause();
        }
        return sawNullState ? Kind.UNAVAILABLE : Kind.OTHER;
    }
}
