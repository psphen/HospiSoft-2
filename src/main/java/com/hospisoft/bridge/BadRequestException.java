package com.hospisoft.bridge;

/**
 * Unchecked: the request coming from the page is malformed (bad JSON, missing field, unknown page).
 * Only this family of errors is reported to the page as BAD_REQUEST with its message; any other
 * unexpected exception becomes INTERNAL_ERROR with a constant message.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
