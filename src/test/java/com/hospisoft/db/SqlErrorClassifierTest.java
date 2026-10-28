package com.hospisoft.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hospisoft.db.SqlErrorClassifier.Kind;
import java.net.ConnectException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class SqlErrorClassifierTest {

    @Test
    void connectionClassIsUnavailable() {
        assertEquals(Kind.UNAVAILABLE, SqlErrorClassifier.classify("08001"));
        assertEquals(Kind.UNAVAILABLE, SqlErrorClassifier.classify("08006"));
        assertEquals(Kind.UNAVAILABLE, SqlErrorClassifier.classify("08S01"));
        assertEquals(Kind.UNAVAILABLE, SqlErrorClassifier.classify("57P01"));
        assertEquals(Kind.UNAVAILABLE, SqlErrorClassifier.classify("57P03"));
    }

    @Test
    void authenticationStatesAreAuth() {
        assertEquals(Kind.AUTH, SqlErrorClassifier.classify("28P01"));
        assertEquals(Kind.AUTH, SqlErrorClassifier.classify("28000"));
    }

    @Test
    void everythingElseIsOther() {
        assertEquals(Kind.OTHER, SqlErrorClassifier.classify("42P01"));
        assertEquals(Kind.OTHER, SqlErrorClassifier.classify("23505"));
        assertEquals(Kind.OTHER, SqlErrorClassifier.classify("57014")); // query canceled, not a crash
        assertEquals(Kind.OTHER, SqlErrorClassifier.classify((String) null));
        assertEquals(Kind.OTHER, SqlErrorClassifier.classify(""));
    }

    @Test
    void exceptionChainIsInspected() {
        SQLException wrapped = new SQLException("wrapper", "XX000", new SQLException("down", "08006"));
        assertEquals(Kind.UNAVAILABLE, SqlErrorClassifier.classify(wrapped));
        SQLException auth = new SQLException("bad password", "28P01");
        assertEquals(Kind.AUTH, SqlErrorClassifier.classify(auth));
        SQLException connectRefused = new SQLException("refused", (String) null, new ConnectException("refused"));
        assertEquals(Kind.UNAVAILABLE, SqlErrorClassifier.classify(connectRefused));
        SQLException nullStateOnly = new SQLException("driver failure", (String) null);
        assertEquals(Kind.UNAVAILABLE, SqlErrorClassifier.classify(nullStateOnly));
        assertEquals(Kind.OTHER, SqlErrorClassifier.classify(new SQLException("syntax", "42601")));
    }
}
