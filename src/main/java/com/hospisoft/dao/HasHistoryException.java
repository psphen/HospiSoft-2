package com.hospisoft.dao;

/**
 * Unchecked: a physical delete was rejected by a foreign key (SQLState 23503) because other rows
 * reference the record. The service answers by deactivating the row instead.
 */
public class HasHistoryException extends RuntimeException {

    public HasHistoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
