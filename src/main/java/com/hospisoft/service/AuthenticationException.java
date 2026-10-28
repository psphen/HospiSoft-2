package com.hospisoft.service;

/** Unchecked: login rejected. The message is always generic and safe to show to the user. */
public class AuthenticationException extends RuntimeException {

    public AuthenticationException(String message) {
        super(message);
    }
}
