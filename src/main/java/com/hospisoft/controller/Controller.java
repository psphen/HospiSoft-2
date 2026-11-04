package com.hospisoft.controller;

import com.hospisoft.bridge.Bridge;

/**
 * MVC controller of one module. The View (the JavaScript pages) reaches it through the
 * {@link Bridge}, which acts as front controller: it queues the call, wraps the result in the
 * envelope and translates exceptions into error codes. A controller only parses the request
 * payload, delegates to its Model (the service) and returns the DTO.
 */
public interface Controller {

    /** Registers this controller's handlers on {@code bridge} under their public method names. */
    void register(Bridge bridge);
}
