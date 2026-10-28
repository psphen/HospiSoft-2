package com.hospisoft.service;

/** Unchecked: the requested row does not exist (any more). Reported as code NOT_FOUND. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
