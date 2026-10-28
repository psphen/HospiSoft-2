package com.hospisoft.service;

/**
 * Result of a delete request. Rows with associated history (citas, historias, user accounts) are
 * never removed physically: they are deactivated instead.
 */
public enum DeleteOutcome {
    DELETED,
    DEACTIVATED
}
