package com.hospisoft.service;

/** Unchecked: the current session is not allowed to perform the operation. Used by later phases. */
public class PermissionException extends RuntimeException {

    public PermissionException(String message) {
        super(message);
    }
}
