package com.hospisoft.db;

/**
 * Unchecked: the database rejected the configured credentials (SQLState 28000 / 28P01).
 * Distinct from {@link DatabaseUnavailableException} so the login page can tell the user to
 * fix config.properties instead of waiting for the server.
 */
public class DatabaseAuthException extends RuntimeException {

    public DatabaseAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
