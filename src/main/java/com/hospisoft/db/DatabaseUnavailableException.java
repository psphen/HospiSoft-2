package com.hospisoft.db;

/**
 * Unchecked: the database cannot be reached (connect failure, dead pool, timeout).
 * Thrown by the db layer, translated by the Bridge into the DB_UNAVAILABLE envelope.
 */
public class DatabaseUnavailableException extends RuntimeException {

    public DatabaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
